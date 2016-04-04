/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.http.scaladsl.unmarshalling.{ FromStringUnmarshaller ⇒ FSU }

private[http] trait ToNameReceptacleEnhancements {
  implicit def symbol2NR(symbol: Symbol): NameReceptacle[String] = new NameReceptacle[String](symbol.name)
  implicit def string2NR(string: String): NameReceptacle[String] = new NameReceptacle[String](string)
}
object ToNameReceptacleEnhancements extends ToNameReceptacleEnhancements

class NameReceptacle[T](val name: String) {
  def as[B] = new NameReceptacle[B](name)
  def as[B](unmarshaller: FSU[B]) = new NameUnmarshallerReceptacle(name, unmarshaller)
  def ? = new NameOptionReceptacle[T](name)
  def ?[B](default: B) = new NameDefaultReceptacle(name, default)
  def ![B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)
  def * = new RepeatedValueReceptacle[T](name)
}

class NameUnmarshallerReceptacle[T](val name: String, val um: FSU[T]) {
  def ? = new NameOptionUnmarshallerReceptacle[T](name, um)
  def ?(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)
  def !(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)
  def * = new RepeatedValueUnmarshallerReceptacle[T](name, um)
}

class NameOptionReceptacle[T](val name: String)

class NameDefaultReceptacle[T](val name: String, val default: T)

class RequiredValueReceptacle[T](val name: String, val requiredValue: T)

class RepeatedValueReceptacle[T](val name: String)

class NameOptionUnmarshallerReceptacle[T](val name: String, val um: FSU[T])

class NameDefaultUnmarshallerReceptacle[T](val name: String, val default: T, val um: FSU[T])

class RequiredValueUnmarshallerReceptacle[T](val name: String, val requiredValue: T, val um: FSU[T])

class RepeatedValueUnmarshallerReceptacle[T](val name: String, val um: FSU[T])
