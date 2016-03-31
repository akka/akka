/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.Supplier
import java.util.{ List ⇒ JList }

import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class RespondWithDirectives extends RangeDirectives {
  import akka.http.impl.util.JavaMapping.Implicits._

  /**
   * Unconditionally adds the given response header to all HTTP responses of its inner Route.
   */
  def respondWithHeader(responseHeader: HttpHeader, inner: Supplier[Route]): Route = RouteAdapter {
    D.respondWithHeader(responseHeader.asScala) { inner.get.delegate }
  }

  /**
   * Adds the given response header to all HTTP responses of its inner Route,
   * if the response from the inner Route doesn't already contain a header with the same name.
   */
  def respondWithDefaultHeader(responseHeader: HttpHeader, inner: Supplier[Route]): Route = RouteAdapter {
    D.respondWithDefaultHeader(responseHeader.asScala) { inner.get.delegate }
  }

  /**
   * Unconditionally adds the given response headers to all HTTP responses of its inner Route.
   */
  def respondWithHeaders(responseHeaders: JIterable[HttpHeader], inner: Supplier[Route]): Route = RouteAdapter {
    D.respondWithHeaders(responseHeaders.asScala) { inner.get.delegate }
  }

  /**
   * Adds the given response headers to all HTTP responses of its inner Route,
   * if a header already exists it is not added again.
   */
  def respondWithDefaultHeaders(responseHeaders: JIterable[HttpHeader], inner: Supplier[Route]): Route = RouteAdapter {
    D.respondWithDefaultHeaders(responseHeaders.asScala.toVector) { inner.get.delegate }
  }

}