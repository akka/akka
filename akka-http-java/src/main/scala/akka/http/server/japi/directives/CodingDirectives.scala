/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi.directives

import akka.http.server.japi.impl.RouteStructure
import akka.http.server.japi.{ Coder, Directive, Directives, Route }

import scala.annotation.varargs

trait CodingDirectives {
  /**
   * Wraps the inner routes with compression support. The response will be compressed
   * using one of the predefined coders, `Gzip`, `Deflate`, or `NoCoding` depending on
   * a potential [[akka.http.model.japi.headers.AcceptEncoding]] header from the client.
   */
  @varargs def compressResponse(innerRoutes: Route*): Route =
    // FIXME: make sure this list stays synchronized with the Scala one
    RouteStructure.EncodeResponse(Vector(Coder.Gzip, Coder.Deflate, Coder.NoCoding), innerRoutes.toVector)

  /**
   * A directive that Wraps its inner routes with compression support.
   * The response will be compressed using one of the given coders with the precedence given
   * by the order of the coders in this call.
   *
   * In any case, a potential [[akka.http.model.japi.headers.AcceptEncoding]] header from the client
   * will be respected (or otherwise, if no matching .
   */
  @varargs def compressResponse(coders: Coder*): Directive =
    Directives.custom(RouteStructure.EncodeResponse(coders.toVector, _))
}
