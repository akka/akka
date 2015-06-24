/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.server.directives._
import scala.collection.immutable
import scala.annotation.varargs
import akka.http.javadsl.model.HttpMethods

abstract class AllDirectives extends WebsocketDirectives

/**
 *
 */
object Directives extends AllDirectives {
  /**
   * INTERNAL API
   */
  private[http] def custom(f: (Route, immutable.Seq[Route]) ⇒ Route): Directive =
    new AbstractDirective {
      def createRoute(first: Route, others: Array[Route]): Route = f(first, others.toList)
    }
}
