/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.util.function.Supplier

import scala.collection.JavaConverters._

import akka.http.javadsl.model.headers.HttpEncoding
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class CodingDirectives extends CacheConditionDirectives {
  /**
   * Rejects the request with an UnacceptedResponseEncodingRejection
   * if the given response encoding is not accepted by the client.
   */
  def responseEncodingAccepted(encoding: HttpEncoding, inner: Supplier[Route]): Route = ScalaRoute {
    D.responseEncodingAccepted(encoding) {
      inner.get.toScala
    }
  }

  /**
   * Encodes the response with the encoding that is requested by the client via the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing or empty or specifies an encoding other than
   * identity, gzip or deflate then no encoding is used.
   */
  def encodeResponse(inner: Supplier[Route]): Route = ScalaRoute {
    D.encodeResponse {
      inner.get.toScala
    }
  }

  /**
   * Encodes the response with the encoding that is requested by the client via the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing then the response is encoded using the `first`
   * encoder.
   *
   * If the `Accept-Encoding` header is empty and `NoCoding` is part of the encoders then no
   * response encoding is used. Otherwise the request is rejected.
   *
   * If [encoders] is empty, no encoding is performed.
   */
  // TODO #19882: provide Java API for creating Encoder, by extracting a Java interface from the Scala trait
  def encodeResponseWith(coders: java.lang.Iterable[Coder], inner: Supplier[Route]): Route = ScalaRoute {
    coders.asScala.toList match {
      case head :: tail ⇒
        D.encodeResponseWith(head._underlyingScalaCoder, tail.toSeq.map(_._underlyingScalaCoder): _*) {
          inner.get.toScala
        }
      case _ ⇒
        inner.get.toScala
    }
  }

  /**
   * Decodes the incoming request using the given Decoder.
   * If the request encoding doesn't match the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  // TODO #19882: provide Java API for creating Decoder, by extracting a Java interface from the Scala trait
  def decodeRequestWith(coder: Coder, inner: Supplier[Route]): Route = ScalaRoute {
    D.decodeRequestWith(coder._underlyingScalaCoder) {
      inner.get.toScala
    }
  }

  /**
   * Rejects the request with an UnsupportedRequestEncodingRejection if its encoding doesn't match the given one.
   */
  def requestEncodedWith(encoding: HttpEncoding, inner: Supplier[Route]): Route = ScalaRoute {
    D.requestEncodedWith(encoding) {
      inner.get.toScala
    }
  }

  /**
   * Decodes the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   * If no decoders are given the default encoders (`Gzip`, `Deflate`, `NoCoding`) are used.
   */
  def decodeRequestWith(coders: java.lang.Iterable[Coder], inner: Supplier[Route]): Route = ScalaRoute {
    D.decodeRequestWith(coders.asScala.map(_._underlyingScalaCoder).toSeq: _*) {
      inner.get.toScala
    }
  }

  /**
   * Decompresses the incoming request if it is `gzip` or `deflate` compressed.
   * Uncompressed requests are passed through untouched.
   * If the request encoded with another encoding the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  def decodeRequest(inner: Supplier[Route]): Route = ScalaRoute {
    D.decodeRequest {
      inner.get.toScala
    }
  }

  /**
   * Inspects the response entity and adds a `Content-Encoding: gzip` response header if
   * the entities media-type is precompressed with gzip and no `Content-Encoding` header is present yet.
   */
  def withPrecompressedMediaTypeSupport(inner: Supplier[Route]): Route = ScalaRoute {
    D.withPrecompressedMediaTypeSupport {
      inner.get.toScala
    }
  }
}

