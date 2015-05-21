/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import java.{ lang ⇒ jl }

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.ParameterDirectives.ParamMagnet
import akka.http.scaladsl.common.ToNameReceptacleEnhancements
import akka.http.impl.server.ParameterImpl

/**
 * A RequestVal representing a query parameter of type T.
 */
trait Parameter[T] extends RequestVal[T]

/**
 * A collection of predefined parameters.
 * FIXME: add tests, see #16437
 */
object Parameters {
  import ToNameReceptacleEnhancements._

  /**
   * A string query parameter.
   */
  def string(name: String): Parameter[String] =
    fromScalaParam(implicit ec ⇒ ParamMagnet(name))

  /**
   * An integer query parameter.
   */
  def integer(name: String): Parameter[jl.Integer] =
    fromScalaParam[jl.Integer](implicit ec ⇒
      ParamMagnet(name.as[Int]).asInstanceOf[ParamMagnet { type Out = Directive1[jl.Integer] }])

  private def fromScalaParam[T: ClassTag](underlying: ExecutionContext ⇒ ParamMagnet { type Out = Directive1[T] }): Parameter[T] =
    new ParameterImpl[T](underlying)
}

