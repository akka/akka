/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import akka.http.scaladsl.unmarshalling.{ Unmarshaller, FromStringUnmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.japi.function
import akka.stream.Materializer

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.Try

object Util {
  def fromStringUnmarshallerFromFunction[T](convert: function.Function[String, T]): FromStringUnmarshaller[T] =
    scalaUnmarshallerFromFunction(convert)

  def scalaUnmarshallerFromFunction[T, U](convert: function.Function[T, U]): Unmarshaller[T, U] =
    new Unmarshaller[T, U] {
      def apply(value: T)(implicit ec: ExecutionContext, mat: Materializer): Future[U] = FastFuture(Try(convert(value)))
    }

  implicit class JApiFunctionAndThen[T, U](f1: function.Function[T, U]) {
    def andThen[V](f2: U ⇒ V): function.Function[T, V] =
      new function.Function[T, V] {
        def apply(param: T): V = f2(f1(param))
      }
  }
}
