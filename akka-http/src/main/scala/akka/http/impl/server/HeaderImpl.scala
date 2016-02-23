/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.util.Optional

import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.Header
import akka.http.scaladsl
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives._
import akka.http.scaladsl.server.directives.RouteDirectives._

import scala.compat.java8.OptionConverters._
import scala.reflect.ClassTag

/**
 * Internal API
 */
private[http] object HeaderImpl {
  def apply[T <: HttpHeader](
    name: String,
    optionalDirective: ClassTag[T with scaladsl.model.HttpHeader] ⇒ Directive1[Option[T with scaladsl.model.HttpHeader]], tClassTag: ClassTag[T]): Header[T] = {
    type U = T with scaladsl.model.HttpHeader

    // cast is safe because creation of javadsl.model.HttpHeader that are not <: scaladsl.model.HttpHeader is forbidden
    implicit def uClassTag: ClassTag[U] = tClassTag.asInstanceOf[ClassTag[U]]

    new Header[U] {
      val instanceDirective: Directive1[U] =
        optionalDirective(uClassTag).flatMap {
          case Some(v) ⇒ provide(v)
          case None    ⇒ reject(MissingHeaderRejection(name))
        }

      def instance(): RequestVal[U] =
        new StandaloneExtractionImpl[U] {
          def directive: Directive1[U] = instanceDirective
        }

      def optionalInstance(): RequestVal[Optional[U]] =
        new StandaloneExtractionImpl[Optional[U]] {
          def directive: Directive1[Optional[U]] = optionalDirective(uClassTag).map(_.asJava)
        }

      def value(): RequestVal[String] =
        new StandaloneExtractionImpl[String] {
          def directive: Directive1[String] = instanceDirective.map(_.value)
        }

      def optionalValue(): RequestVal[Optional[String]] =
        new StandaloneExtractionImpl[Optional[String]] {
          def directive: Directive1[Optional[String]] = optionalDirective(uClassTag).map(_.map(_.value).asJava)
        }
    }.asInstanceOf[Header[T]] // undeclared covariance
  }
}
