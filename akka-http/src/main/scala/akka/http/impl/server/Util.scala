/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.scaladsl.unmarshalling.{ Unmarshaller, FromStringUnmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.japi.function.Function

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Try

object Util {
  def fromStringUnmarshallerFromFunction[T](convert: Function[String, T]): FromStringUnmarshaller[T] =
    scalaUnmarshallerFromFunction(convert)
  def scalaUnmarshallerFromFunction[T, U](convert: Function[T, U]): Unmarshaller[T, U] =
    new Unmarshaller[T, U] {
      def apply(value: T)(implicit ec: ExecutionContext): Future[U] = FastFuture(Try(convert(value)))
    }
}
