/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import akka.http.javadsl.server.Directive
import akka.http.javadsl.server.Directives
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server._

import scala.annotation.varargs
import akka.http.scaladsl
import akka.http.impl.server.RouteStructure

abstract class CodingDirectives extends CacheConditionDirectives {
  /**
   * Wraps the inner routes with encoding support. The response will be encoded
   * using one of the predefined coders, `Gzip`, `Deflate`, or `NoCoding` depending on
   * a potential [[akka.http.javadsl.model.headers.AcceptEncoding]] header from the client.
   */
  @varargs def encodeResponse(innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.EncodeResponse(CodingDirectives._DefaultCodersToEncodeResponse)(innerRoute, moreInnerRoutes.toList)

  /**
   * A directive that Wraps its inner routes with encoding support.
   * The response will be encoded using one of the given coders with the precedence given
   * by the order of the coders in this call.
   *
   * In any case, a potential [[akka.http.javadsl.model.headers.AcceptEncoding]] header from the client
   * will be respected (or otherwise, if no matching .
   */
  @varargs def encodeResponse(coders: Coder*): Directive =
    Directives.custom(RouteStructure.EncodeResponse(coders.toList))

  /**
   * Decodes the incoming request using the given Decoder.
   * If the request encoding doesn't match the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  @varargs def decodeRequestWith(decoder: Coder, innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.DecodeRequest(decoder :: Nil)(innerRoute, moreInnerRoutes.toList)

  /**
   * Decodes the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   * If no decoders are given the default encoders (`Gzip`, `Deflate`, `NoCoding`) are used.
   */
  @varargs def decodeRequestWith(decoders: Coder*): Directive =
    Directives.custom(RouteStructure.DecodeRequest(decoders.toList))

  /**
   * Decompresses the incoming request if it is `gzip` or `deflate` compressed.
   * Uncompressed requests are passed through untouched.
   * If the request encoded with another encoding the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  @varargs def decodeRequest(innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.DecodeRequest(CodingDirectives._DefaultCodersToDecodeRequest)(innerRoute, moreInnerRoutes.toList)
}

/**
 * Internal API
 */
private[http] object CodingDirectives {
  private[http] val _DefaultCodersToEncodeResponse =
    scaladsl.server.directives.CodingDirectives.DefaultEncodeResponseEncoders
      .map(c ⇒ Coder.values().find(_._underlyingScalaCoder() == c).get)

  private[http] val _DefaultCodersToDecodeRequest =
    scaladsl.server.directives.CodingDirectives.DefaultCoders
      .map(c ⇒ Coder.values().find(_._underlyingScalaCoder() == c).get)
}
