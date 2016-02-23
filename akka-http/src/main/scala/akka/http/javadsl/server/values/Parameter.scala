/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values

import java.util.AbstractMap.SimpleEntry
import java.util.{ Collection ⇒ JCollection, Map ⇒ JMap, Optional }
import java.{ lang ⇒ jl }

import akka.http.impl.server.{ ParameterImpl, StandaloneExtractionImpl, Util }
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.server.directives.ParameterDirectives
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.japi.function.Function

import scala.reflect.ClassTag

/**
 * A RequestVal representing a query parameter of type T.
 */
trait Parameter[T] extends RequestVal[T] {
  def optional: RequestVal[Optional[T]]
  def withDefault(defaultValue: T): RequestVal[T]
}

/**
 * A collection of predefined parameters.
 * FIXME: add tests, see #16437
 */
object Parameters {
  import akka.http.scaladsl.common.ToNameReceptacleEnhancements._

  /**
   * A string query parameter.
   */
  def stringValue(name: String): Parameter[String] = ParameterImpl(name)

  /**
   * An integer query parameter.
   */
  def intValue(name: String): Parameter[jl.Integer] = ParameterImpl(name.as[Int])
  def byteValue(name: String): Parameter[jl.Byte] = ParameterImpl(name.as[Byte])
  def shortValue(name: String): Parameter[jl.Short] = ParameterImpl(name.as[Short])
  def longValue(name: String): Parameter[jl.Long] = ParameterImpl(name.as[Long])
  def floatValue(name: String): Parameter[jl.Float] = ParameterImpl(name.as[Float])
  def doubleValue(name: String): Parameter[jl.Double] = ParameterImpl(name.as[Double])
  def booleanValue(name: String): Parameter[jl.Boolean] = ParameterImpl(name.as[Boolean])

  def hexByteValue(name: String): Parameter[jl.Byte] = ParameterImpl(name.as(Unmarshaller.HexByte))
  def hexShortValue(name: String): Parameter[jl.Short] = ParameterImpl(name.as(Unmarshaller.HexShort))
  def hexIntValue(name: String): Parameter[jl.Integer] = ParameterImpl(name.as(Unmarshaller.HexInt))
  def hexLongValue(name: String): Parameter[jl.Long] = ParameterImpl(name.as(Unmarshaller.HexLong))

  import scala.collection.JavaConverters._
  def asMap: RequestVal[JMap[String, String]] = StandaloneExtractionImpl(ParameterDirectives.parameterMap.map(_.asJava))
  def asMultiMap: RequestVal[JMap[String, JCollection[String]]] =
    StandaloneExtractionImpl(ParameterDirectives.parameterMultiMap.map(_.mapValues(_.asJavaCollection).asJava))
  def asCollection: RequestVal[JCollection[JMap.Entry[String, String]]] =
    StandaloneExtractionImpl(ParameterDirectives.parameterSeq.map(_.map(e ⇒ new SimpleEntry(e._1, e._2): JMap.Entry[String, String]).asJavaCollection))

  /** Unmarshals the `name` field using the provided `convert` function. */
  def fromString[T](name: String, clazz: Class[T], convert: Function[String, T]): Parameter[T] = {
    implicit val tTag: ClassTag[T] = ClassTag(clazz)
    ParameterImpl(name.as(Util.fromStringUnmarshallerFromFunction(convert)))
  }
}

