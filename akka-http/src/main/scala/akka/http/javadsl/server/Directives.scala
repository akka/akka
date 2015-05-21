/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.server.directives._
import scala.collection.immutable
import scala.annotation.varargs
import akka.http.javadsl.model.HttpMethods

// FIXME: add support for the remaining directives, see #16436
abstract class AllDirectives extends PathDirectives

/**
 *
 */
object Directives extends AllDirectives {
  /**
   * INTERNAL API
   */
  private[http] def custom(f: immutable.Seq[Route] ⇒ Route): Directive =
    new AbstractDirective {
      def createRoute(first: Route, others: Array[Route]): Route = f(first +: others.toVector)
    }
}
