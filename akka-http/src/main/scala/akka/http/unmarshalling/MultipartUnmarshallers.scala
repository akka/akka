/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import org.reactivestreams.Publisher
import scala.concurrent.Future
import akka.actor.ActorRefFactory
import akka.http.parsing.BodyPartParser
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.http.model._
import akka.http.util._
import MediaRanges._
import MediaTypes._
import HttpCharsets._

trait MultipartUnmarshallers {

  implicit def defaultMultipartContentUnmarshaller(implicit fm: FlowMaterializer,
                                                   refFactory: ActorRefFactory) = multipartContentUnmarshaller(`UTF-8`)
  def multipartContentUnmarshaller(defaultCharset: HttpCharset)(implicit fm: FlowMaterializer,
                                                                refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartContent] =
    multipartPartsUnmarshaller[MultipartContent](`multipart/*`, ContentTypes.`text/plain` withCharset defaultCharset)(MultipartContent(_))

  implicit def defaultMultipartByteRangesUnmarshaller(implicit fm: FlowMaterializer,
                                                      refFactory: ActorRefFactory) = multipartByteRangesUnmarshaller(`UTF-8`)
  def multipartByteRangesUnmarshaller(defaultCharset: HttpCharset)(implicit fm: FlowMaterializer,
                                                                   refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartByteRanges] =
    multipartPartsUnmarshaller[MultipartByteRanges](`multipart/byteranges`,
      ContentTypes.`text/plain` withCharset defaultCharset)(MultipartByteRanges(_))

  def multipartPartsUnmarshaller[T <: MultipartParts](mediaRange: MediaRange, defaultContentType: ContentType)(create: Publisher[BodyPart] ⇒ T)(implicit fm: FlowMaterializer,
                                                                                                                                                refFactory: ActorRefFactory): FromEntityUnmarshaller[T] =
    Unmarshaller { entity ⇒
      Future.successful {
        if (mediaRange matches entity.contentType.mediaType) {
          entity.contentType.mediaType.params.get("boundary") match {
            case None ⇒ sys.error("Content-Type with a multipart media type must have a 'boundary' parameter")
            case Some(boundary) ⇒
              val bodyParts = Flow(entity.dataBytes(fm))
                .transform(new BodyPartParser(defaultContentType, boundary, fm, actorSystem(refFactory).log))
                .splitWhen(_.isInstanceOf[BodyPartParser.BodyPartStart])
                .headAndTail(fm)
                .collect {
                  case (BodyPartParser.BodyPartStart(headers, createEntity), entityParts) ⇒
                    BodyPart(createEntity(entityParts), headers)
                  case (BodyPartParser.ParseError(errorInfo), _) ⇒ throw new ParsingException(errorInfo)
                }.toPublisher()(fm)
              Unmarshalling.Success(create(bodyParts))
          }
        } else Unmarshalling.UnsupportedContentType(ContentTypeRange(mediaRange) :: Nil)
      }
    }

  implicit def defaultMultipartFormDataUnmarshaller(implicit fm: FlowMaterializer,
                                                    refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartFormData] =
    multipartFormDataUnmarshaller(verifyIntegrity = true)
  def multipartFormDataUnmarshaller(verifyIntegrity: Boolean = true)(implicit fm: FlowMaterializer,
                                                                     refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartFormData] =
    multipartPartsUnmarshaller(`multipart/form-data`, ContentTypes.`application/octet-stream`) { bodyParts ⇒
      def verify(part: BodyPart): BodyPart = part // TODO
      val parts = if (verifyIntegrity) Flow(bodyParts).map(verify).toPublisher()(fm) else bodyParts
      MultipartFormData(parts)
    }

  implicit def defaultStrictMultipartFormDataUnmarshaller(implicit fm: FlowMaterializer,
                                                          refFactory: ActorRefFactory): FromEntityUnmarshaller[StrictMultipartFormData] =
    strictMultipartFormDataUnmarshaller(verifyIntegrity = true)
  def strictMultipartFormDataUnmarshaller(verifyIntegrity: Boolean = true)(implicit fm: FlowMaterializer,
                                                                           refFactory: ActorRefFactory): FromEntityUnmarshaller[StrictMultipartFormData] = {
    implicit val ec = actorSystem(refFactory).dispatcher
    val m = multipartFormDataUnmarshaller(verifyIntegrity)
    Unmarshaller {
      m(_) flatMap {
        case Unmarshalling.Success(mfd) ⇒ mfd.toStrict(fm).map(Unmarshalling.Success.apply)
        case e: Unmarshalling.Failure   ⇒ Future.successful(e)
      }
    }
  }

}

object MultipartUnmarshallers extends MultipartUnmarshallers