/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.function.{ BooleanSupplier, Function ⇒ JFunction, Supplier }

import akka.http.javadsl.model.RemoteAddress
import akka.http.javadsl.model.headers.Language
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }

import scala.collection.JavaConverters._

abstract class TimeoutDirectives extends WebSocketDirectives {

  def withoutRequestTimeout(inner: Supplier[Route]): ScalaRoute = ScalaRoute {
    D.withoutRequestTimeout { inner.get.toScala }
  }

}
