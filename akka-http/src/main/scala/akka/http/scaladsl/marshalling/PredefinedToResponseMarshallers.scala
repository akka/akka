/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import akka.event.Logging
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.model
import akka.http.scaladsl.model.ContentType.{ Binary, WithFixedCharset }
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.impl.ConstantFun
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.collection.immutable
import scala.language.higherKinds
import scala.reflect.ClassTag

trait PredefinedToResponseMarshallers extends LowPriorityToResponseMarshallerImplicits {
  import PredefinedToResponseMarshallers._

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

  def fromToEntityMarshaller[T](
    status:  StatusCode                = StatusCodes.OK,
    headers: immutable.Seq[HttpHeader] = Nil)(
    implicit
    m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    fromStatusCodeAndHeadersAndValue compose (t ⇒ (status, headers, t))

  implicit val fromResponse: TRM[HttpResponse] = Marshaller.opaque(ConstantFun.scalaIdentityFunction)

  /**
   * Creates a response for a status code. Does not support content-type negotiation but will return
   * a response either with a `text-plain` entity containing the `status.defaultMessage` or an empty entity
   * for status codes that don't allow a response.
   */
  implicit val fromStatusCode: TRM[StatusCode] =
    Marshaller.opaque { status ⇒ statusCodeResponse(status) }

  /**
   * Creates a response from status code and headers. Does not support content-type negotiation but will return
   * a response either with a `text-plain` entity containing the `status.defaultMessage` or an empty entity
   * for status codes that don't allow a response.
   */
  implicit val fromStatusCodeAndHeaders: TRM[(StatusCode, immutable.Seq[HttpHeader])] =
    Marshaller.opaque { case (status, headers) ⇒ statusCodeResponse(status, headers) }

  implicit def fromStatusCodeAndValue[S, T](implicit sConv: S ⇒ StatusCode, mt: ToEntityMarshaller[T]): TRM[(S, T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, value) ⇒ (sConv(status), Nil, value) }

  implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit
    sConv: S ⇒ StatusCode,
                                                             mt: ToEntityMarshaller[T]): TRM[(S, immutable.Seq[HttpHeader], T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, headers, value) ⇒ (sConv(status), headers, value) }

  implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(StatusCode, immutable.Seq[HttpHeader], T)] =
    Marshaller(implicit ec ⇒ {
      case (status, headers, value) ⇒ mt(value).fast map (_ map (_ map (statusCodeAndEntityResponse(status, headers, _))))
    })

  implicit def fromEntityStreamingSupportAndByteStringMarshaller[T, M](implicit s: EntityStreamingSupport, m: ToByteStringMarshaller[T]): ToResponseMarshaller[Source[T, M]] = {
    Marshaller[Source[T, M], HttpResponse] { implicit ec ⇒ source ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(s.contentType, () ⇒ {
          val availableMarshallingsPerElement = source.mapAsync(1) { t ⇒ m(t)(ec) }

          // TODO optimise such that we pick the optimal marshalling only once (headAndTail needed?)
          // TODO, NOTE: this is somewhat duplicated from Marshal.scala it could be made DRYer
          val bestMarshallingPerElement = availableMarshallingsPerElement mapConcat { marshallings ⇒
            // pick the Marshalling that matches our EntityStreamingSupport
            (s.contentType match {
              case best @ (_: ContentType.Binary | _: ContentType.WithFixedCharset) ⇒
                marshallings collectFirst { case Marshalling.WithFixedContentType(`best`, marshal) ⇒ marshal }

              case best @ ContentType.WithCharset(bestMT, bestCS) ⇒
                marshallings collectFirst {
                  case Marshalling.WithFixedContentType(`best`, marshal) ⇒ marshal
                  case Marshalling.WithOpenCharset(`bestMT`, marshal)    ⇒ () ⇒ marshal(bestCS)
                }
            }).toList
          }
          val marshalledElements: Source[ByteString, M] =
            bestMarshallingPerElement.map(_.apply()) // marshal!
              .via(s.framingRenderer)

          HttpResponse(entity = HttpEntity(s.contentType, marshalledElements))
        }) :: Nil
      }
    }
  }
}

trait LowPriorityToResponseMarshallerImplicits {
  implicit def liftMarshallerConversion[T](m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    liftMarshaller(m)
  implicit def liftMarshaller[T](implicit m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    PredefinedToResponseMarshallers.fromToEntityMarshaller()

  @deprecated("This method exists only for the purpose of binary compatibility, it used to be implicit.", "10.0.2")
  def fromEntityStreamingSupportAndEntityMarshaller[T, M](s: EntityStreamingSupport, m: ToEntityMarshaller[T]): ToResponseMarshaller[Source[T, M]] =
    fromEntityStreamingSupportAndEntityMarshaller(s, m, null)

  // FIXME deduplicate this!!!
  implicit def fromEntityStreamingSupportAndEntityMarshaller[T, M](implicit s: EntityStreamingSupport, m: ToEntityMarshaller[T], tag: ClassTag[T]): ToResponseMarshaller[Source[T, M]] = {
    Marshaller[Source[T, M], HttpResponse] { implicit ec ⇒ source ⇒
      FastFuture successful {
        Marshalling.WithFixedContentType(s.contentType, () ⇒ {
          val availableMarshallingsPerElement = source.mapAsync(1) { t ⇒ m(t)(ec) }

          // TODO optimise such that we pick the optimal marshalling only once (headAndTail needed?)
          // TODO, NOTE: this is somewhat duplicated from Marshal.scala it could be made DRYer
          val bestMarshallingPerElement = availableMarshallingsPerElement mapConcat { marshallings ⇒
            // pick the Marshalling that matches our EntityStreamingSupport
            val selectedMarshallings = (s.contentType match {
              case best @ (_: ContentType.Binary | _: ContentType.WithFixedCharset) ⇒
                marshallings collectFirst { case Marshalling.WithFixedContentType(`best`, marshal) ⇒ marshal }

              case best @ ContentType.WithCharset(bestMT, bestCS) ⇒
                marshallings collectFirst {
                  case Marshalling.WithFixedContentType(`best`, marshal) ⇒ marshal
                  case Marshalling.WithOpenCharset(`bestMT`, marshal)    ⇒ () ⇒ marshal(bestCS)
                }
            }).toList

            // TODO we could either special case for certrain known types,
            // or extend the entity support to be more advanced such that it would negotiate the element content type it
            // is able to render.
            if (selectedMarshallings.isEmpty) throw new NoStrictlyCompatibleElementMarshallingAvailableException[T](s.contentType, marshallings)
            else selectedMarshallings
          }

          val marshalledElements: Source[ByteString, M] =
            bestMarshallingPerElement.map(_.apply()) // marshal!
              .flatMapConcat(_.dataBytes) // extract raw dataBytes
              .via(s.framingRenderer)

          HttpResponse(entity = HttpEntity(s.contentType, marshalledElements))
        }) :: Nil
      }
    }
  }
}

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers {
  /** INTERNAL API */
  private def statusCodeResponse(statusCode: StatusCode, headers: immutable.Seq[HttpHeader] = Nil): HttpResponse = {
    val entity =
      if (statusCode.allowsEntity) HttpEntity(statusCode.defaultMessage)
      else HttpEntity.Empty

    HttpResponse(status = statusCode, headers = headers, entity = entity)
  }

  private def statusCodeAndEntityResponse(statusCode: StatusCode, headers: immutable.Seq[HttpHeader] = Nil, entity: ResponseEntity = HttpEntity.Empty): HttpResponse = {
    if (statusCode.allowsEntity) HttpResponse(statusCode, headers, entity)
    else HttpResponse(statusCode, headers, HttpEntity.Empty)
  }
}

final class NoStrictlyCompatibleElementMarshallingAvailableException[T](
  streamContentType:     ContentType,
  availableMarshallings: List[Marshalling[_]])(implicit tag: ClassTag[T])
  extends RuntimeException(
    s"None of the available marshallings ($availableMarshallings) directly " +
      s"match the ContentType requested by the top-level streamed entity ($streamContentType). " +
      s"Please provide an implicit `Marshaller[${if (tag == null) "T" else tag.runtimeClass.getName}, HttpEntity]` " +
      s"that can render ${if (tag == null) "" else tag.runtimeClass.getName + " "}" +
      s"as [$streamContentType]")
