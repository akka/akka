/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model._
import akka.http.routing
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }

package object unmarshalling {
  type Deserialized[T] = Future[T]
  type FromStringDeserializer[T] = Deserializer[String, T]
  type FromStringOptionDeserializer[T] = Deserializer[Option[String], T]
  type FromEntityOptionUnmarshaller[T] = Deserializer[Option[HttpEntity], T]
  type FromBodyPartOptionUnmarshaller[T] = Deserializer[Option[BodyPart], T]
  type FromMessageUnmarshaller[T] = Deserializer[HttpMessage, T]
  trait FormFieldConverter[T] {
    def withDefault(default: T): FormFieldConverter[T]
  }

  def unmarshalUnsafe[T: Unmarshaller](entity: HttpEntity): T = routing.FIXME

  implicit class RequestAddAs(req: HttpRequest) {
    def as[T](implicit um: FromRequestUnmarshaller[T]): Deserialized[T] = um(req)
  }
  implicit class ResponseAddAs(res: HttpResponse) {
    def as[T](implicit um: FromResponseUnmarshaller[T]): Deserialized[T] = um(res)
  }
  implicit class EntityAddAs(ent: HttpEntity) {
    def as[T](implicit um: Unmarshaller[T]): Deserialized[T] = um(ent)
    def asString(implicit mat: FlowMaterializer, ec: ExecutionContext): Future[String] = as[String]

    def collectedDataBytes(implicit mat: FlowMaterializer): Future[ByteString] =
      Flow(ent.dataBytes(mat)).fold(ByteString.empty)(_ ++ _).toFuture(mat)
  }
}

package unmarshalling {
  case class MalformedContent(errorMessage: String, cause: Option[Throwable] = None) extends DeserializationError

  object MalformedContent {
    def apply(errorMessage: String, cause: Throwable): MalformedContent = new MalformedContent(errorMessage, Some(cause))
  }

  trait Unmarshaller[T] extends Deserializer[HttpEntity, T]
  object Unmarshaller {
    implicit def byteArrayUnmarshaller(implicit mat: FlowMaterializer, ec: ExecutionContext): Unmarshaller[Array[Byte]] =
      Unmarshaller(ContentTypeRange.`*`) {
        // FIXME: optimize cases where contentLength is available up front by pre-allocating exactly one array
        case x ⇒ x.collectedDataBytes.map(_.toArray[Byte])
      }

    implicit def stringUnmarshaller(implicit mat: FlowMaterializer, ec: ExecutionContext): Unmarshaller[String] =
      Unmarshaller.delegate[Array[Byte], String](ContentTypeRange.`*`) { bs ⇒
        println(s"Trying to convert $bs of length ${bs.length}")
        new String(bs, "UTF-8")
      }

    implicit def byteRangesUnmarshaller: Unmarshaller[MultipartByteRanges] = routing.FIXME
    implicit def xmlUnmarshaller: Unmarshaller[scala.xml.NodeSeq] = routing.FIXME
    implicit def entityUnmarshaller: Unmarshaller[HttpEntity] = routing.FIXME
    implicit def optionUnmarshaller[T: Unmarshaller]: Unmarshaller[Option[T]] = routing.FIXME

    def apply[T](unmarshalFrom: ContentTypeRange*)(f: PartialFunction[HttpEntity, Future[T]])(implicit ec: ExecutionContext): Unmarshaller[T] =
      new SimpleUnmarshaller[T] {
        val canUnmarshalFrom = unmarshalFrom
        def unmarshal(entity: HttpEntity) =
          if (f.isDefinedAt(entity)) protect(f(entity)) else Future.failed(ContentExpected)
      }

    def delegate[A, B](unmarshalFrom: ContentTypeRange*)(f: A ⇒ B)(implicit mb: Unmarshaller[A], ec: ExecutionContext): Unmarshaller[B] =
      new SimpleUnmarshaller[B] {
        val canUnmarshalFrom = unmarshalFrom
        def unmarshal(entity: HttpEntity) = mb(entity).flatMap(a ⇒ protect(Future.successful(f(a))))
      }
  }

  trait FromRequestUnmarshaller[T] extends Deserializer[HttpRequest, T]
  object FromRequestUnmarshaller {
    implicit def fromUnmarshaller[T: Unmarshaller]: FromRequestUnmarshaller[T] =
      new FromRequestUnmarshaller[T] {
        def apply(v1: HttpRequest): Deserialized[T] = implicitly[Unmarshaller[T]].apply(v1.entity)
      }
  }
  trait FromResponseUnmarshaller[T] extends Deserializer[HttpResponse, T]
  object FromResponseUnmarshaller {
    implicit def fromUnmarshaller[T: Unmarshaller]: FromResponseUnmarshaller[T] =
      new FromResponseUnmarshaller[T] {
        def apply(v1: HttpResponse): Deserialized[T] = implicitly[Unmarshaller[T]].apply(v1.entity)
      }
  }
}