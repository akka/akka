/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.io.File
import java.util.function.BiFunction
import java.util.{ List ⇒ JList }
import java.lang.{ Iterable ⇒ JIterable }
import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import akka.http.javadsl.model.ContentType
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.stream.javadsl.Source
import akka.util.ByteString
import akka.http.javadsl.model.HttpHeader
import java.util.function.Supplier

abstract class RespondWithDirectives extends RangeDirectives {
  /**
   * Unconditionally adds the given response header to all HTTP responses of its inner Route.
   */
  def respondWithHeader(responseHeader: HttpHeader, inner: Supplier[Route]): Route = ScalaRoute {
    D.respondWithHeader(responseHeader) { inner.get.toScala }
  }

  /**
   * Adds the given response header to all HTTP responses of its inner Route,
   * if the response from the inner Route doesn't already contain a header with the same name.
   */
  def respondWithDefaultHeader(responseHeader: HttpHeader, inner: Supplier[Route]): Route = ScalaRoute {
    D.respondWithDefaultHeader(responseHeader) { inner.get.toScala }
  }

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   */
  def respondWithHeaders(responseHeaders: JIterable[HttpHeader], inner: Supplier[Route]): Route = ScalaRoute {
    D.respondWithHeaders(responseHeaders.asScala.toVector) { inner.get.toScala }
  }

  /**
   * Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   */
  def respondWithDefaultHeaders(responseHeaders: JIterable[HttpHeader], inner: Supplier[Route]): Route = ScalaRoute {
    D.respondWithDefaultHeaders(responseHeaders.asScala.toVector) { inner.get.toScala }
  }

}