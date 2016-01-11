/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.common

import akka.http.unmarshalling.{ FromStringUnmarshaller ⇒ FSU }

private[http] trait ToNameReceptacleEnhancements {
  implicit def symbol2NR(symbol: Symbol) = new NameReceptacle[String](symbol.name)
  implicit def string2NR(string: String) = new NameReceptacle[String](string)
}

class NameReceptacle[T](val name: String) {
  def as[B] = new NameReceptacle[B](name)
  def as[B](unmarshaller: FSU[B]) = new NameUnmarshallerReceptacle(name, unmarshaller)
  def ? = new NameOptionReceptacle[T](name)
  def ?[B](default: B) = new NameDefaultReceptacle(name, default)
  def ![B](requiredValue: B) = new RequiredValueReceptacle(name, requiredValue)
}

class NameUnmarshallerReceptacle[T](val name: String, val um: FSU[T]) {
  def ? = new NameOptionUnmarshallerReceptacle[T](name, um)
  def ?(default: T) = new NameDefaultUnmarshallerReceptacle(name, default, um)
  def !(requiredValue: T) = new RequiredValueUnmarshallerReceptacle(name, requiredValue, um)
}

class NameOptionReceptacle[T](val name: String)

class NameDefaultReceptacle[T](val name: String, val default: T)

class RequiredValueReceptacle[T](val name: String, val requiredValue: T)

class NameOptionUnmarshallerReceptacle[T](val name: String, val um: FSU[T])

class NameDefaultUnmarshallerReceptacle[T](val name: String, val default: T, val um: FSU[T])

class RequiredValueUnmarshallerReceptacle[T](val name: String, val requiredValue: T, val um: FSU[T])