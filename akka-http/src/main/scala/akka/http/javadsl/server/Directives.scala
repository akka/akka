/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.server.directives._
import scala.collection.immutable

abstract class AllDirectives extends WebSocketDirectives

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
