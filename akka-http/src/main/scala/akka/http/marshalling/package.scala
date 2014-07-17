/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import model._

package object marshalling {
  def marshalUnsafe[T: Marshaller](value: T): HttpEntity = routing.FIXME

  def marshaller[T](implicit m: Marshaller[T]): Marshaller[T] = m

  def marshalToEntity[T: Marshaller](value: T): Either[Throwable, HttpEntity.Regular] =
    marshaller[T].marshal(value)
}

package marshalling {

  import scala.concurrent.Future

  trait Marshaller[-T] {
    def marshal(value: T): Either[Throwable, HttpEntity.Regular]
  }
  object Marshaller {
    implicit def stringMarshaller: Marshaller[String] =
      new Marshaller[String] {
        def marshal(value: String): Either[Throwable, HttpEntity.Regular] = Right(HttpEntity(value))
      }
    implicit def bytesMarshaller: Marshaller[Array[Byte]] = routing.FIXME
    implicit def xmlMarshaller: Marshaller[scala.xml.NodeSeq] = routing.FIXME
    implicit def formDataMarshaller: Marshaller[FormData] = routing.FIXME
    implicit def entityMarshaller: Marshaller[HttpEntity] = routing.FIXME
    implicit def multipartByteRangesMarshaller: Marshaller[MultipartByteRanges] = routing.FIXME
  }

  trait ToResponseMarshaller[-T] {
    def marshal(value: T): Either[Throwable, HttpResponse]
  }
  object ToResponseMarshaller {
    implicit def fromMarshaller[T: Marshaller]: ToResponseMarshaller[T] =
      new ToResponseMarshaller[T] {
        def marshal(value: T): Either[Throwable, HttpResponse] =
          marshaller[T].marshal(value).right.map { entity ⇒
            HttpResponse(entity = entity)
          }
      }

    implicit def responseMarshaller: ToResponseMarshaller[HttpResponse] =
      new ToResponseMarshaller[HttpResponse] {
        def marshal(value: HttpResponse): Either[Throwable, HttpResponse] = Right(value)
      }

    implicit def fromStatusCode: ToResponseMarshaller[StatusCode] =
      new ToResponseMarshaller[StatusCode] {
        def marshal(value: StatusCode): Either[Throwable, HttpResponse] = Right(HttpResponse(status = value))
      }
    implicit def fromStatusCodeAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T]): ToResponseMarshaller[(S, T)] = routing.FIXME
    implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T]): ToResponseMarshaller[(S, Seq[HttpHeader], T)] = routing.FIXME
    implicit def fromStatusCodeAndHeadersAndT[T](implicit tMarshaller: Marshaller[T]): ToResponseMarshaller[(StatusCode, Seq[HttpHeader], T)] = routing.FIXME

    implicit def optionMarshaller[T: ToResponseMarshaller]: ToResponseMarshaller[Option[T]] = routing.FIXME
    implicit def futureMarshaller[T: ToResponseMarshaller]: ToResponseMarshaller[Future[T]] = routing.FIXME
  }

  /** Something that can later be marshalled into a response */
  trait ToResponseMarshallable {
    def marshal: Either[Throwable, HttpResponse]
  }
  object ToResponseMarshallable {
    implicit def isMarshallable[T](value: T)(implicit marshaller: ToResponseMarshaller[T]): ToResponseMarshallable =
      new ToResponseMarshallable {
        def marshal: Either[Throwable, HttpResponse] = marshaller.marshal(value)
      }
    implicit def marshallableIsMarshallable: ToResponseMarshaller[ToResponseMarshallable] =
      new ToResponseMarshaller[ToResponseMarshallable] {
        def marshal(value: ToResponseMarshallable): Either[Throwable, HttpResponse] = value.marshal
      }
  }
}
