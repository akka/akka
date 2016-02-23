/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.util.Optional

import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.Parameter
import akka.http.scaladsl.common.{ NameUnmarshallerReceptacle, NameReceptacle }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling._

import scala.reflect.ClassTag
import akka.http.scaladsl.server.directives.ParameterDirectives
import akka.http.scaladsl.server.Directive1

import scala.compat.java8.OptionConverters._

/**
 * INTERNAL API
 */
private[http] class ParameterImpl[T, U](receptacle: NameReceptacle[T])(
  implicit fu: FromStringUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U)
  extends StandaloneExtractionImpl[U] with Parameter[U] {

  import ParameterDirectives._
  def directive: Directive1[U] = parameter(receptacle).map(conv)

  def optional: RequestVal[Optional[U]] =
    new StandaloneExtractionImpl[Optional[U]] {
      def directive: Directive1[Optional[U]] = optionalDirective
    }

  private def optionalDirective: Directive1[Optional[U]] =
    extractMaterializer.flatMap { implicit fm ⇒
      parameter(receptacle.?).map(v ⇒ v.map(conv).asJava)
    }

  def withDefault(defaultValue: U): RequestVal[U] =
    new StandaloneExtractionImpl[U] {
      def directive: Directive1[U] = optionalDirective.map(_.orElse(defaultValue))
    }
}
private[http] object ParameterImpl {
  def apply[T, U](receptacle: NameReceptacle[T])(implicit fu: FromStringUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U): Parameter[U] =
    new ParameterImpl(receptacle)(fu, tTag, conv)

  def apply[T, U](receptacle: NameUnmarshallerReceptacle[T])(implicit tTag: ClassTag[U], conv: T ⇒ U): Parameter[U] =
    new ParameterImpl(new NameReceptacle(receptacle.name))(receptacle.um, tTag, conv)
}