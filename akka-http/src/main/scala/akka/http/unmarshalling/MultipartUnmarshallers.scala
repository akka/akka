/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import scala.concurrent.ExecutionContext
import akka.event.{ NoLogging, LoggingAdapter }
import akka.http.engine.parsing.BodyPartParser
import akka.http.model._
import akka.http.util._
import akka.stream.scaladsl._
import MediaRanges._
import MediaTypes._
import HttpCharsets._
import akka.stream.impl.fusing.IteratorInterpreter
import akka.util.ByteString

trait MultipartUnmarshallers {

  implicit def defaultMultipartGeneralUnmarshaller(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[Multipart.General] =
    multipartGeneralUnmarshaller(`UTF-8`)
  def multipartGeneralUnmarshaller(defaultCharset: HttpCharset)(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[Multipart.General] =
    multipartUnmarshaller[Multipart.General, Multipart.General.BodyPart, Multipart.General.BodyPart.Strict](
      mediaRange = `multipart/*`,
      defaultContentType = ContentTypes.`text/plain` withCharset defaultCharset,
      createBodyPart = Multipart.General.BodyPart(_, _),
      createStreamed = Multipart.General(_, _),
      createStrictBodyPart = Multipart.General.BodyPart.Strict,
      createStrict = Multipart.General.Strict)

  implicit def multipartFormDataUnmarshaller(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[Multipart.FormData] =
    multipartUnmarshaller[Multipart.FormData, Multipart.FormData.BodyPart, Multipart.FormData.BodyPart.Strict](
      mediaRange = `multipart/form-data`,
      defaultContentType = ContentTypes.`application/octet-stream`,
      createBodyPart = (entity, headers) ⇒ Multipart.General.BodyPart(entity, headers).toFormDataBodyPart.get,
      createStreamed = (_, parts) ⇒ Multipart.FormData(parts),
      createStrictBodyPart = (entity, headers) ⇒ Multipart.General.BodyPart.Strict(entity, headers).toFormDataBodyPart.get,
      createStrict = (_, parts) ⇒ Multipart.FormData.Strict(parts))

  implicit def defaultMultipartByteRangesUnmarshaller(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[Multipart.ByteRanges] =
    multipartByteRangesUnmarshaller(`UTF-8`)
  def multipartByteRangesUnmarshaller(defaultCharset: HttpCharset)(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[Multipart.ByteRanges] =
    multipartUnmarshaller[Multipart.ByteRanges, Multipart.ByteRanges.BodyPart, Multipart.ByteRanges.BodyPart.Strict](
      mediaRange = `multipart/byteranges`,
      defaultContentType = ContentTypes.`text/plain` withCharset defaultCharset,
      createBodyPart = (entity, headers) ⇒ Multipart.General.BodyPart(entity, headers).toByteRangesBodyPart.get,
      createStreamed = (_, parts) ⇒ Multipart.ByteRanges(parts),
      createStrictBodyPart = (entity, headers) ⇒ Multipart.General.BodyPart.Strict(entity, headers).toByteRangesBodyPart.get,
      createStrict = (_, parts) ⇒ Multipart.ByteRanges.Strict(parts))

  def multipartUnmarshaller[T <: Multipart, BP <: Multipart.BodyPart, BPS <: Multipart.BodyPart.Strict](mediaRange: MediaRange,
                                                                                                        defaultContentType: ContentType,
                                                                                                        createBodyPart: (BodyPartEntity, List[HttpHeader]) ⇒ BP,
                                                                                                        createStreamed: (MultipartMediaType, Source[BP]) ⇒ T,
                                                                                                        createStrictBodyPart: (HttpEntity.Strict, List[HttpHeader]) ⇒ BPS,
                                                                                                        createStrict: (MultipartMediaType, immutable.Seq[BPS]) ⇒ T)(implicit ec: ExecutionContext, log: LoggingAdapter = NoLogging): FromEntityUnmarshaller[T] =
    Unmarshaller { entity ⇒
      if (entity.contentType.mediaType.isMultipart && mediaRange.matches(entity.contentType.mediaType)) {
        entity.contentType.mediaType.params.get("boundary") match {
          case None ⇒
            FastFuture.failed(new RuntimeException("Content-Type with a multipart media type must have a 'boundary' parameter"))
          case Some(boundary) ⇒
            import BodyPartParser._
            val parser = new BodyPartParser(defaultContentType, boundary, log)
            FastFuture.successful {
              entity match {
                case HttpEntity.Strict(ContentType(mediaType: MultipartMediaType, _), data) ⇒
                  val builder = new VectorBuilder[BPS]()
                  val iter = new IteratorInterpreter[ByteString, BodyPartParser.Output](
                    Iterator.single(data), List(parser)).iterator
                  // note that iter.next() will throw exception if stream fails
                  iter.foreach {
                    case BodyPartStart(headers, createEntity) ⇒
                      val entity = createEntity(Source.empty()) match {
                        case x: HttpEntity.Strict ⇒ x
                        case x                    ⇒ throw new IllegalStateException("Unexpected entity type from strict BodyPartParser: " + x)
                      }
                      builder += createStrictBodyPart(entity, headers)
                    case ParseError(errorInfo) ⇒ throw new ParsingException(errorInfo)
                    case x                     ⇒ throw new IllegalStateException(s"Unexpected BodyPartParser result $x in strict case")
                  }
                  createStrict(mediaType, builder.result())
                case _ ⇒
                  val bodyParts = entity.dataBytes
                    .transform(() ⇒ parser)
                    .splitWhen(_.isInstanceOf[BodyPartStart])
                    .headAndTail
                    .collect {
                      case (BodyPartStart(headers, createEntity), entityParts) ⇒ createBodyPart(createEntity(entityParts), headers)
                      case (ParseError(errorInfo), _)                          ⇒ throw new ParsingException(errorInfo)
                    }
                  createStreamed(entity.contentType.mediaType.asInstanceOf[MultipartMediaType], bodyParts)
              }
            }
        }
      } else FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(mediaRange))
    }
}

object MultipartUnmarshallers extends MultipartUnmarshallers
