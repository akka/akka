/*
 * CopyFuture.successful (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.collection.immutable
import akka.http.model.HttpEntity.Regular

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

import model._

package object marshalling {
  def marshalUnsafe[T: Marshaller](value: T): HttpEntity = routing.FIXME

  def marshaller[T](implicit m: Marshaller[T]): Marshaller[T] = m

  def marshalToEntity[T](value: T)(implicit tMarshaller: Marshaller[T]): Future[HttpEntity.Regular] =
    tMarshaller.marshal(value)
}

package marshalling {

  trait Marshaller[-T] { outer ⇒
    def marshal(value: T): Future[HttpEntity.Regular]

    def compose[U](f: U ⇒ T): Marshaller[U] =
      new Marshaller[U] {
        def marshal(value: U): Future[Regular] =
          try outer.marshal(f(value))
          catch {
            case NonFatal(ex) ⇒ Future.failed(ex)
          }
      }
  }
  object Marshaller {
    implicit def stringMarshaller: Marshaller[String] =
      new Marshaller[String] {
        def marshal(value: String): Future[HttpEntity.Regular] = Future.successful(HttpEntity(value))
      }
    implicit def bytesMarshaller: Marshaller[Array[Byte]] = routing.FIXME
    implicit def xmlMarshaller: Marshaller[scala.xml.NodeSeq] = routing.FIXME
    implicit def formDataMarshaller: Marshaller[FormData] = routing.FIXME
    implicit def entityMarshaller: Marshaller[HttpEntity] = routing.FIXME
    implicit def multipartByteRangesMarshaller: Marshaller[MultipartByteRanges] = routing.FIXME
  }

  trait ToResponseMarshaller[-T] { outer ⇒
    def marshal(value: T): Future[HttpResponse]

    def compose[U](f: U ⇒ T): ToResponseMarshaller[U] =
      new ToResponseMarshaller[U] {
        def marshal(value: U): Future[HttpResponse] =
          try outer.marshal(f(value))
          catch {
            case NonFatal(ex) ⇒ Future.failed(ex)
          }
      }
  }
  object ToResponseMarshaller {
    def apply[T](f: T ⇒ HttpResponse): ToResponseMarshaller[T] =
      new ToResponseMarshaller[T] {
        def marshal(value: T): Future[HttpResponse] = Future.successful(f(value))
      }

    implicit def fromMarshaller[T](implicit tMarshaller: Marshaller[T], ec: ExecutionContext): ToResponseMarshaller[T] =
      new ToResponseMarshaller[T] {
        def marshal(value: T): Future[HttpResponse] =
          tMarshaller.marshal(value).map { entity ⇒
            HttpResponse(entity = entity)
          }
      }

    implicit def responseMarshaller: ToResponseMarshaller[HttpResponse] =
      new ToResponseMarshaller[HttpResponse] {
        def marshal(value: HttpResponse): Future[HttpResponse] = Future.successful(value)
      }

    implicit def fromStatusCode(implicit ec: ExecutionContext): ToResponseMarshaller[StatusCode] =
      ToResponseMarshaller[StatusCode](s ⇒ HttpResponse(status = s, entity = s.defaultMessage))

    implicit def fromStatusCodeAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T], ec: ExecutionContext): ToResponseMarshaller[(S, T)] =
      fromStatusCodeAndHeadersAndT.compose {
        case (s, t) ⇒ (sConv(s), Nil, t)
      }
    implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S ⇒ StatusCode, tMarshaller: Marshaller[T], ec: ExecutionContext): ToResponseMarshaller[(S, Seq[HttpHeader], T)] =
      fromStatusCodeAndHeadersAndT.compose {
        case (s, hs, t) ⇒ (sConv(s), hs, t)
      }
    implicit def fromStatusCodeAndHeadersAndT[T](implicit tMarshaller: Marshaller[T], ec: ExecutionContext): ToResponseMarshaller[(StatusCode, Seq[HttpHeader], T)] =
      new ToResponseMarshaller[(StatusCode, Seq[HttpHeader], T)] {
        def marshal(value: (StatusCode, Seq[HttpHeader], T)): Future[HttpResponse] =
          tMarshaller.marshal(value._3).map(t ⇒ HttpResponse(value._1, value._2.toList, t))
      }

    implicit def optionMarshaller[T: ToResponseMarshaller]: ToResponseMarshaller[Option[T]] = routing.FIXME
    implicit def futureMarshaller[T](implicit tMarshaller: ToResponseMarshaller[T], ec: ExecutionContext): ToResponseMarshaller[Future[T]] =
      new ToResponseMarshaller[Future[T]] {
        def marshal(value: Future[T]): Future[HttpResponse] = value.flatMap(tMarshaller.marshal)
      }
  }

  /** Something that can later be marshalled into a response */
  trait ToResponseMarshallable {
    def marshal: Future[HttpResponse]
    def executionContext: ExecutionContext
  }
  object ToResponseMarshallable {
    implicit def isMarshallable[T](value: T)(implicit marshaller: ToResponseMarshaller[T], ec: ExecutionContext): ToResponseMarshallable =
      new ToResponseMarshallable {
        def marshal: Future[HttpResponse] = marshaller.marshal(value)
        def executionContext: ExecutionContext = ec
      }
    implicit def marshallableIsMarshallable: ToResponseMarshaller[ToResponseMarshallable] =
      new ToResponseMarshaller[ToResponseMarshallable] {
        def marshal(value: ToResponseMarshallable): Future[HttpResponse] = value.marshal

      }
  }
}
