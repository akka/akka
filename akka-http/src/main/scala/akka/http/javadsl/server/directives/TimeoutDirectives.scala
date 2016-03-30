/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.{ BooleanSupplier, Function ⇒ JFunction, Supplier }

import akka.http.javadsl.model.{ HttpRequest, HttpResponse, RemoteAddress }
import akka.http.javadsl.model.headers.Language
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }

import akka.http.impl.util.JavaMapping
import akka.http.impl.util.JavaMapping.Implicits._
import scala.collection.JavaConverters._

abstract class TimeoutDirectives extends WebSocketDirectives {

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeout(timeout: scala.concurrent.duration.Duration, inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withRequestTimeout(timeout) { inner.get.delegate }
  }

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeout(timeout: scala.concurrent.duration.Duration, timeoutHandler: JFunction[HttpRequest, HttpResponse],
                         inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withRequestTimeout(timeout, in ⇒ timeoutHandler(in.asJava).asScala) { inner.get.delegate }
  }

  def withoutRequestTimeout(inner: Supplier[Route]): RouteAdapter = RouteAdapter {
    D.withoutRequestTimeout { inner.get.delegate }
  }

}
