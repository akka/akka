/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model._

package object unmarshalling {
  type Deserialized[T] = Either[DeserializationError, T]
  type FromStringDeserializer[T] = Deserializer[String, T]
  type FromStringOptionDeserializer[T] = Deserializer[Option[String], T]
  type Unmarshaller[T] = Deserializer[HttpEntity, T]
  type FromEntityOptionUnmarshaller[T] = Deserializer[Option[HttpEntity], T]
  type FromBodyPartOptionUnmarshaller[T] = Deserializer[Option[BodyPart], T]
  type FromMessageUnmarshaller[T] = Deserializer[HttpMessage, T]
  type FromRequestUnmarshaller[T] = Deserializer[HttpRequest, T]
  type FromResponseUnmarshaller[T] = Deserializer[HttpResponse, T]

  trait FormFieldConverter[T] {
    def withDefault(default: T): FormFieldConverter[T]
  }

  implicit class AddAs(req: HttpRequest) {
    def as[T](implicit um: FromRequestUnmarshaller[T]): Deserialized[T] = ???
  }

  case class MalformedContent(errorMessage: String, cause: Option[Throwable] = None) extends DeserializationError

  object MalformedContent {
    def apply(errorMessage: String, cause: Throwable): MalformedContent = new MalformedContent(errorMessage, Some(cause))
  }
}
