/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.server.util.Tuple

/**
 * A Route that can be implicitly converted into a Directive (fitting any signature).
 */
abstract class StandardRoute extends Route {
  def toDirective[L: Tuple]: Directive[L] = StandardRoute.toDirective(this)
}

object StandardRoute {
  def apply(route: Route): StandardRoute = route match {
    case x: StandardRoute ⇒ x
    case x                ⇒ new StandardRoute { def apply(ctx: RequestContext) = x(ctx) }
  }

  /**
   * Converts the StandardRoute into a directive that never passes the request to its inner route
   * (and always returns its underlying route).
   */
  implicit def toDirective[L: Tuple](route: StandardRoute): Directive[L] =
    Directive[L] { _ ⇒ route }
}