/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.impl.server.RouteStructure.Redirect
import akka.http.javadsl.model.{ StatusCode, Uri }

abstract class RouteDirectives extends RangeDirectives {
  /**
   * Completes the request with redirection response of the given type to the given URI.
   *
   * The ``redirectionType`` must be a StatusCode for which ``isRedirection`` returns true.
   */
  def redirect(uri: Uri, redirectionType: StatusCode): Route = Redirect(uri, redirectionType)
}
