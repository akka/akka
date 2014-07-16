/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model._
import akka.http.routing

package object unmarshalling {
  type Deserialized[T] = Either[DeserializationError, T]
  type FromStringDeserializer[T] = Deserializer[String, T]
  type FromStringOptionDeserializer[T] = Deserializer[Option[String], T]
  type FromEntityOptionUnmarshaller[T] = Deserializer[Option[HttpEntity], T]
  type FromBodyPartOptionUnmarshaller[T] = Deserializer[Option[BodyPart], T]
  type FromMessageUnmarshaller[T] = Deserializer[HttpMessage, T]
  trait FormFieldConverter[T] {
    def withDefault(default: T): FormFieldConverter[T]
  }

  implicit class RequestAddAs(req: HttpRequest) {
    def as[T](implicit um: FromRequestUnmarshaller[T]): Deserialized[T] = routing.FIXME
  }
  implicit class ResponseAddAs(req: HttpResponse) {
    def as[T](implicit um: FromResponseUnmarshaller[T]): Deserialized[T] = routing.FIXME
  }
  implicit class EntityAddAs(req: HttpEntity) {
    def as[T](implicit um: Unmarshaller[T]): Deserialized[T] = routing.FIXME
    def asString: String = routing.FIXME
  }

  case class MalformedContent(errorMessage: String, cause: Option[Throwable] = None) extends DeserializationError

  object MalformedContent {
    def apply(errorMessage: String, cause: Throwable): MalformedContent = new MalformedContent(errorMessage, Some(cause))
  }

  trait Unmarshaller[T] extends Deserializer[HttpEntity, T]
  object Unmarshaller {
    implicit def stringUnmarshaller: Unmarshaller[String] = routing.FIXME
    implicit def byteRangesUnmarshaller: Unmarshaller[MultipartByteRanges] = routing.FIXME
    implicit def xmlUnmarshaller: Unmarshaller[scala.xml.NodeSeq] = routing.FIXME
    implicit def entityUnmarshaller: Unmarshaller[HttpEntity] = routing.FIXME
    implicit def optionUnmarshaller[T: Unmarshaller]: Unmarshaller[Option[T]] = routing.FIXME
    implicit def byteArrayUnmarshaller: Unmarshaller[Array[Byte]] = routing.FIXME
  }

  trait FromRequestUnmarshaller[T] extends Deserializer[HttpRequest, T]
  object FromRequestUnmarshaller {
    implicit def fromUnmarshaller[T: Unmarshaller]: FromRequestUnmarshaller[T] = routing.FIXME
  }
  trait FromResponseUnmarshaller[T] extends Deserializer[HttpResponse, T]
  object FromResponseUnmarshaller {
    implicit def fromUnmarshaller[T: Unmarshaller]: FromResponseUnmarshaller[T] = routing.FIXME
  }

  def unmarshalUnsafe[T: Unmarshaller](entity: HttpEntity): T = routing.FIXME
}
