/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.collection.immutable

import model._

package object marshalling {
  def marshalUnsafe[T: Marshaller](value: T): HttpEntity = routing.FIXME

  def marshaller[T](implicit m: Marshaller[T]): Marshaller[T] = m

  def marshalToEntity[T: Marshaller](value: T): Either[Throwable, HttpEntity.Regular] =
    marshaller[T].marshal(value)
}

package marshalling {

  import akka.http.model.HttpEntity.Regular

  import scala.concurrent.Future
  import scala.util.control.NonFatal

  trait Marshaller[-T] { outer ⇒
    def marshal(value: T): Either[Throwable, HttpEntity.Regular]

    def compose[U](f: U ⇒ T): Marshaller[U] =
      new Marshaller[U] {
        def marshal(value: U): Either[Throwable, Regular] =
          try outer.marshal(f(value))
          catch {
            case NonFatal(ex) ⇒ Left(ex)
          }
      }
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

  trait ToResponseMarshaller[-T] { outer ⇒
    def marshal(value: T): Either[Throwable, HttpResponse]

    def compose[U](f: U ⇒ T): ToResponseMarshaller[U] =
      new ToResponseMarshaller[U] {
        def marshal(value: U): Either[Throwable, HttpResponse] =
          try outer.marshal(f(value))
          catch {
            case NonFatal(ex) ⇒ Left(ex)
          }
      }
  }
  object ToResponseMarshaller {
    def apply[T](f: T ⇒ HttpResponse): ToResponseMarshaller[T] =
      new ToResponseMarshaller[T] {
        def marshal(value: T): Either[Throwable, HttpResponse] = Right(f(value))
      }

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
      ToResponseMarshaller[StatusCode](s ⇒ HttpResponse(status = s, entity = s.defaultMessage))

    implicit def fromStatusCodeAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T]): ToResponseMarshaller[(S, T)] =
      fromStatusCodeAndHeadersAndT.compose {
        case (s, t) ⇒ (sConv(s), Nil, t)
      }
    implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T]): ToResponseMarshaller[(S, Seq[HttpHeader], T)] =
      fromStatusCodeAndHeadersAndT.compose {
        case (s, hs, t) ⇒ (sConv(s), hs, t)
      }
    implicit def fromStatusCodeAndHeadersAndT[T](implicit tMarshaller: Marshaller[T]): ToResponseMarshaller[(StatusCode, Seq[HttpHeader], T)] =
      new ToResponseMarshaller[(StatusCode, Seq[HttpHeader], T)] {
        def marshal(value: (StatusCode, Seq[HttpHeader], T)): Either[Throwable, HttpResponse] =
          tMarshaller.marshal(value._3).right.map(t ⇒ HttpResponse(value._1, value._2.toList, t))
      }

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
