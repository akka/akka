/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

trait DeserializationError extends Exception

trait Deserializer[A, B] extends (A ⇒ Deserialized[B]) {
  def withDefaultValue(defaultValue: B): Deserializer[A, B]
}
case class UnsupportedContentType(errorMessage: String) extends DeserializationError
case object ContentExpected extends DeserializationError