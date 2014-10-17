/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.ExecutionContext
import akka.http.unmarshalling.{ FromStringOptionUnmarshaller ⇒ FSOU, Unmarshaller }

trait ToNameReceptacleEnhancements {
  implicit def symbol2NR(symbol: Symbol) = new NameReceptacle[String](symbol.name)
  implicit def string2NR(string: String) = new NameReceptacle[String](string)
}

case class NameReceptacle[A](name: String) {
  def as[B] = NameReceptacle[B](name)
  def as[B](unmarshaller: FSOU[B]) = NameUnmarshallerReceptacle(name, _ ⇒ unmarshaller)
  def ? = as[Option[A]]
  def ?[B](default: B) = NameDefaultReceptacle(name, default)
  def ![B](requiredValue: B) = RequiredValueReceptacle(name, requiredValue)
}

case class NameUnmarshallerReceptacle[A](name: String, um: ExecutionContext ⇒ FSOU[A]) {
  def ? = NameUnmarshallerReceptacle(name, ec ⇒ Unmarshaller.targetOptionUnmarshaller(um(ec), ec))
  def ?(default: A) = NameUnmarshallerDefaultReceptacle(name, um, default)
  def !(requiredValue: A) = RequiredValueUnmarshallerReceptacle(name, um, requiredValue)
}

case class NameDefaultReceptacle[A](name: String, default: A)

case class RequiredValueReceptacle[A](name: String, requiredValue: A)

case class NameUnmarshallerDefaultReceptacle[A](name: String, um: ExecutionContext ⇒ FSOU[A], default: A)

case class RequiredValueUnmarshallerReceptacle[A](name: String, um: ExecutionContext ⇒ FSOU[A], requiredValue: A)