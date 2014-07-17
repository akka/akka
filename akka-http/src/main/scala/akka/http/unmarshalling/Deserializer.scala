/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.routing

import scala.concurrent.{ ExecutionContext, Future }

trait DeserializationError extends Exception

trait Deserializer[A, B] extends (A ⇒ Deserialized[B]) { self ⇒
  def withDefaultValue(defaultValue: B)(implicit ec: ExecutionContext): Deserializer[A, B] =
    new Deserializer[A, B] {
      def apply(value: A) = self(value).recoverWith {
        case ContentExpected ⇒ Future.successful(defaultValue)
        case error           ⇒ Future.failed(error)
      }
    }
}
object Deserializer extends FromStringDeserializers {
  implicit def trivial: FromStringOptionDeserializer[String] = routing.FIXME

  implicit def liftFromStringDeserializer[T: FromStringDeserializer]: FromStringOptionDeserializer[T] = routing.FIXME
  implicit def liftFromStringDeserializerConversion[T](f: FromStringDeserializer[T]): FromStringOptionDeserializer[T] = routing.FIXME
}
case class UnsupportedContentType(errorMessage: String) extends DeserializationError
case object ContentExpected extends DeserializationError

trait FromStringDeserializers {
  implicit def String2IntConverter: FromStringDeserializer[Int] = routing.FIXME
  def HexInt: FromStringDeserializer[Int] = routing.FIXME
}
object FromStringDeserializers extends FromStringDeserializers