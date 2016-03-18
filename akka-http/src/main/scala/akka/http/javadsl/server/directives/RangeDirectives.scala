/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure.RangeSupport

import scala.annotation.varargs

abstract class RangeDirectives extends PathDirectives {
  /**
   * Answers GET requests with an `Accept-Ranges: bytes` header and converts HttpResponses coming back from its inner
   * route into partial responses if the initial request contained a valid `Range` request header. The requested
   * byte-ranges may be coalesced.
   * This directive is transparent to non-GET requests
   * Rejects requests with unsatisfiable ranges `UnsatisfiableRangeRejection`.
   * Rejects requests with too many expected ranges.
   *
   * Note: if you want to combine this directive with `conditional(...)` you need to put
   * it on the *inside* of the `conditional(...)` directive, i.e. `conditional(...)` must be
   * on a higher level in your route structure in order to function correctly.
   *
   * For more information, see: https://tools.ietf.org/html/rfc7233
   */
  @varargs def withRangeSupport(innerRoute: Route, moreInnerRoutes: Route*): Route = RangeSupport()(innerRoute, moreInnerRoutes.toList)
}
