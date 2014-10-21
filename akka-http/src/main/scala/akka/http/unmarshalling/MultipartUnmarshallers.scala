/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.actor.ActorRefFactory
import akka.stream.scaladsl2._
import akka.http.engine.parsing.BodyPartParser
import akka.http.model._
import akka.http.util._
import MediaRanges._
import MediaTypes._
import HttpCharsets._

trait MultipartUnmarshallers {

  implicit def defaultMultipartContentUnmarshaller(implicit refFactory: ActorRefFactory) = multipartContentUnmarshaller(`UTF-8`)
  def multipartContentUnmarshaller(defaultCharset: HttpCharset)(implicit refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartContent] =
    multipartPartsUnmarshaller[MultipartContent](`multipart/*`, ContentTypes.`text/plain` withCharset defaultCharset)(MultipartContent(_))

  implicit def defaultMultipartByteRangesUnmarshaller(implicit refFactory: ActorRefFactory) = multipartByteRangesUnmarshaller(`UTF-8`)
  def multipartByteRangesUnmarshaller(defaultCharset: HttpCharset)(implicit refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartByteRanges] =
    multipartPartsUnmarshaller[MultipartByteRanges](`multipart/byteranges`,
      ContentTypes.`text/plain` withCharset defaultCharset)(MultipartByteRanges(_))

  def multipartPartsUnmarshaller[T <: MultipartParts](mediaRange: MediaRange, defaultContentType: ContentType)(create: Source[BodyPart] ⇒ T)(implicit refFactory: ActorRefFactory): FromEntityUnmarshaller[T] =
    Unmarshaller { entity ⇒
      if (mediaRange matches entity.contentType.mediaType) {
        entity.contentType.mediaType.params.get("boundary") match {
          case None ⇒ FastFuture.failed(UnmarshallingError.InvalidContent("Content-Type with a multipart media type must have a 'boundary' parameter"))
          case Some(boundary) ⇒
            val bodyParts = entity.dataBytes
              .transform("bodyPart", () ⇒ new BodyPartParser(defaultContentType, boundary, actorSystem(refFactory).log))
              .splitWhen(_.isInstanceOf[BodyPartParser.BodyPartStart])
              .headAndTail
              .collect {
                case (BodyPartParser.BodyPartStart(headers, createEntity), entityParts) ⇒
                  BodyPart(createEntity(entityParts), headers)
                case (BodyPartParser.ParseError(errorInfo), _) ⇒ throw new ParsingException(errorInfo)
              }
            FastFuture.successful(create(bodyParts))
        }
      } else FastFuture.failed(UnmarshallingError.UnsupportedContentType(ContentTypeRange(mediaRange) :: Nil))
    }

  implicit def defaultMultipartFormDataUnmarshaller(implicit refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartFormData] =
    multipartFormDataUnmarshaller(verifyIntegrity = true)
  def multipartFormDataUnmarshaller(verifyIntegrity: Boolean = true)(implicit refFactory: ActorRefFactory): FromEntityUnmarshaller[MultipartFormData] =
    multipartPartsUnmarshaller(`multipart/form-data`, ContentTypes.`application/octet-stream`) { bodyParts ⇒
      def verify(part: BodyPart): BodyPart = part // TODO
      val parts = if (verifyIntegrity) bodyParts.map(verify) else bodyParts
      MultipartFormData(parts)
    }

  implicit def defaultStrictMultipartFormDataUnmarshaller(implicit fm: FlowMaterializer,
                                                          refFactory: ActorRefFactory): FromEntityUnmarshaller[StrictMultipartFormData] =
    strictMultipartFormDataUnmarshaller(verifyIntegrity = true)
  def strictMultipartFormDataUnmarshaller(verifyIntegrity: Boolean = true)(implicit fm: FlowMaterializer,
                                                                           refFactory: ActorRefFactory): FromEntityUnmarshaller[StrictMultipartFormData] = {
    implicit val ec = actorSystem(refFactory).dispatcher
    multipartFormDataUnmarshaller(verifyIntegrity) flatMap (mfd ⇒ mfd.toStrict())
  }

}

object MultipartUnmarshallers extends MultipartUnmarshallers