/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import akka.http.scaladsl.common.EntityStreamingSupport
import akka.stream.impl.ConstantFun

import scala.collection.immutable
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.ContentNegotiator
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.language.higherKinds

trait PredefinedToResponseMarshallers extends LowPriorityToResponseMarshallerImplicits {

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

  def fromToEntityMarshaller[T](
    status:  StatusCode                = StatusCodes.OK,
    headers: immutable.Seq[HttpHeader] = Nil)(
    implicit
    m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    fromStatusCodeAndHeadersAndValue compose (t ⇒ (status, headers, t))

  implicit val fromResponse: TRM[HttpResponse] = Marshaller.opaque(ConstantFun.scalaIdentityFunction)

  implicit val fromStatusCode: TRM[StatusCode] =
    Marshaller.withOpenCharset(`text/plain`) { (status, charset) ⇒
      HttpResponse(status, entity = HttpEntity(ContentType(`text/plain`, charset), status.defaultMessage))
    }

  implicit def fromStatusCodeAndValue[S, T](implicit sConv: S ⇒ StatusCode, mt: ToEntityMarshaller[T]): TRM[(S, T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, value) ⇒ (sConv(status), Nil, value) }

  implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit
    sConv: S ⇒ StatusCode,
                                                             mt: ToEntityMarshaller[T]): TRM[(S, immutable.Seq[HttpHeader], T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, headers, value) ⇒ (sConv(status), headers, value) }

  implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(StatusCode, immutable.Seq[HttpHeader], T)] =
    Marshaller(implicit ec ⇒ {
      case (status, headers, value) ⇒ mt(value).fast map (_ map (_ map (HttpResponse(status, headers, _))))
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

  // FIXME deduplicate this!!!
  implicit def fromEntityStreamingSupportAndEntityMarshaller[T, M](implicit s: EntityStreamingSupport, m: ToEntityMarshaller[T]): ToResponseMarshaller[Source[T, M]] = {
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
              .flatMapConcat(_.dataBytes) // extract raw dataBytes
              .via(s.framingRenderer)

          HttpResponse(entity = HttpEntity(s.contentType, marshalledElements))
        }) :: Nil
      }
    }
  }

}

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers
