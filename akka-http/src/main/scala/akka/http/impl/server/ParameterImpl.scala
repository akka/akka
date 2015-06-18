/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.Parameter
import akka.http.scaladsl.common.{ NameUnmarshallerReceptacle, NameReceptacle }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._
import akka.japi.{ Option ⇒ JOption }

import scala.reflect.ClassTag
import akka.http.scaladsl.server.directives.ParameterDirectives
import akka.http.scaladsl.server.Directive1

/**
 * INTERNAL API
 */
private[http] class ParameterImpl[T, U](receptacle: NameReceptacle[T])(
  implicit fu: FromStringUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U)
  extends StandaloneExtractionImpl[U] with Parameter[U] {

  import ParameterDirectives._
  def directive: Directive1[U] = parameter(receptacle).map(conv)

  def optional: RequestVal[JOption[U]] =
    new StandaloneExtractionImpl[JOption[U]] {
      def directive: Directive1[JOption[U]] = optionalDirective
    }

  private def optionalDirective: Directive1[JOption[U]] =
    extractMaterializer.flatMap { implicit fm ⇒
      parameter(receptacle.?).map(v ⇒ JOption.fromScalaOption(v.map(conv)))
    }

  def withDefault(defaultValue: U): RequestVal[U] =
    new StandaloneExtractionImpl[U] {
      def directive: Directive1[U] = optionalDirective.map(_.getOrElse(defaultValue))
    }
}
private[http] object ParameterImpl {
  def apply[T, U](receptacle: NameReceptacle[T])(implicit fu: FromStringUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U): Parameter[U] =
    new ParameterImpl(receptacle)(fu, tTag, conv)

  def apply[T, U](receptacle: NameUnmarshallerReceptacle[T])(implicit tTag: ClassTag[U], conv: T ⇒ U): Parameter[U] =
    new ParameterImpl(new NameReceptacle(receptacle.name))(receptacle.um, tTag, conv)
}