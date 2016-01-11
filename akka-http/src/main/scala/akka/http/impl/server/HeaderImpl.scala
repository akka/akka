/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.Header
import akka.http.scaladsl
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives._
import akka.http.scaladsl.server.directives.RouteDirectives._

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

      def optionalInstance(): RequestVal[Option[U]] =
        new StandaloneExtractionImpl[Option[U]] {
          def directive: Directive1[Option[U]] = optionalDirective(uClassTag)
        }

      def value(): RequestVal[String] =
        new StandaloneExtractionImpl[String] {
          def directive: Directive1[String] = instanceDirective.map(_.value)
        }

      def optionalValue(): RequestVal[Option[String]] =
        new StandaloneExtractionImpl[Option[String]] {
          def directive: Directive1[Option[String]] = optionalDirective(uClassTag).map(_.map(_.value))
        }
    }.asInstanceOf[Header[T]] // undeclared covariance
  }
}
