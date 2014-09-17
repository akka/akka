/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.server

import scala.concurrent.{ ExecutionContext, Future }

// FIXME: we may want to merge stuff from here with the general Unmarshalling infrastructure
trait Deserializer[A, B] extends (A ⇒ Deserialized[B]) { self ⇒
  def withDefaultValue(defaultValue: B)(implicit ec: ExecutionContext): Deserializer[A, B] =
    new Deserializer[A, B] {
      def apply(value: A) = self(value).recoverWith {
        case DeserializationError.ContentExpected ⇒ Future.successful(defaultValue)
        case error                                ⇒ Future.failed(error)
      }
    }
}
object Deserializer extends FromStringDeserializers {
  implicit def trivial: FromStringOptionDeserializer[String] =
    new FromStringOptionDeserializer[String] {
      def apply(v1: Option[String]): Deserialized[String] = v1 match {
        case Some(s) ⇒ Future.successful(s)
        case None    ⇒ Future.failed(DeserializationError.ContentExpected)
      }
    }

  implicit def liftFromStringDeserializer[T: FromStringDeserializer]: FromStringOptionDeserializer[T] = server.FIXME
  implicit def liftFromStringDeserializerConversion[T](f: FromStringDeserializer[T]): FromStringOptionDeserializer[T] = server.FIXME
}

trait DeserializationError extends RuntimeException
object DeserializationError {
  case object ContentExpected extends DeserializationError
}
case class UnsupportedContentType(errorMessage: String) extends DeserializationError
case class MalformedContent(errorMessage: String, cause: Option[Throwable] = None) extends DeserializationError

trait FromStringDeserializers {
  implicit def String2IntConverter: FromStringDeserializer[Int] = server.FIXME
  def HexInt: FromStringDeserializer[Int] = server.FIXME
}
object FromStringDeserializers extends FromStringDeserializers