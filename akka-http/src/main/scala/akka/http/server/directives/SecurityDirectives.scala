/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import BasicDirectives._
import RouteDirectives._

trait SecurityDirectives {
  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   */
  def authorize(check: ⇒ Boolean): Directive0 = authorize(_ ⇒ check)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   */
  def authorize(check: RequestContext ⇒ Boolean): Directive0 =
    extract(check).flatMap[Unit](if (_) pass else reject(AuthorizationFailedRejection)) &
      cancelRejection(AuthorizationFailedRejection)
}
