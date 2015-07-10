/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package values

import java.{ lang ⇒ jl }
import akka.japi.function.Function
import akka.japi.{ Option ⇒ JOption }

import akka.http.impl.server.{ Util, FormFieldImpl }
import akka.http.scaladsl.unmarshalling._

import scala.reflect.ClassTag

trait FormField[T] extends RequestVal[T] {
  def optional: RequestVal[JOption[T]]
  def withDefault(defaultValue: T): RequestVal[T]
}

object FormFields {
  import akka.http.scaladsl.common.ToNameReceptacleEnhancements._

  def stringValue(name: String): FormField[String] = FormFieldImpl(name)
  def intValue(name: String): FormField[jl.Integer] = FormFieldImpl(name.as[Int])
  def byteValue(name: String): FormField[jl.Byte] = FormFieldImpl(name.as[Byte])
  def shortValue(name: String): FormField[jl.Short] = FormFieldImpl(name.as[Short])
  def longValue(name: String): FormField[jl.Long] = FormFieldImpl(name.as[Long])
  def floatValue(name: String): FormField[jl.Float] = FormFieldImpl(name.as[Float])
  def doubleValue(name: String): FormField[jl.Double] = FormFieldImpl(name.as[Double])
  def booleanValue(name: String): FormField[jl.Boolean] = FormFieldImpl(name.as[Boolean])

  def hexByteValue(name: String): FormField[jl.Byte] = FormFieldImpl(name.as(Unmarshaller.HexByte))
  def hexShortValue(name: String): FormField[jl.Short] = FormFieldImpl(name.as(Unmarshaller.HexShort))
  def hexIntValue(name: String): FormField[jl.Integer] = FormFieldImpl(name.as(Unmarshaller.HexInt))
  def hexLongValue(name: String): FormField[jl.Long] = FormFieldImpl(name.as(Unmarshaller.HexLong))

  def fromString[T](name: String, convert: Function[String, T], clazz: Class[T]): FormField[T] = {
    implicit val tTag: ClassTag[T] = ClassTag(clazz)
    FormFieldImpl(name.as(Util.fromStringUnmarshallerFromFunction(convert)))
  }
}