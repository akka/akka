/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class SchemeDirectives extends RouteDirectives {
  /**
   * Extracts the Uri scheme from the request.
   */
  def extractScheme(inner: JFunction[String, Route]): Route = ScalaRoute {
    D.extractScheme { s ⇒ inner.apply(s).toScala }
  }

  /**
   * Rejects all requests whose Uri scheme does not match the given one.
   */
  def scheme(name: String, inner: Supplier[Route]): Route = ScalaRoute {
    D.scheme(name) { inner.get().toScala }
  }
}
