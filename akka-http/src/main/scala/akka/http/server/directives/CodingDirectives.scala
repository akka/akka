/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.util._
import akka.http.model._
import headers.HttpEncoding
import akka.http.coding._
import scala.util.control.NonFatal

trait CodingDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._
  import CodingDirectives._

  // encoding

  /**
   * Wraps its inner Route with encoding support using the given Encoder.
   */
  def encodeResponse(encoder: Encoder): Directive0 =
    responseEncodingAccepted(encoder.encoding) &
      mapResponse(encoder.encode(_)) &
      cancelRejections(classOf[UnacceptedResponseEncodingRejection])

  /**
   * Rejects the request with an UnacceptedResponseEncodingRejection
   * if the given encoding is not accepted for the response.
   */
  def responseEncodingAccepted(encoding: HttpEncoding): Directive0 =
    extract(_.request.isEncodingAccepted(encoding))
      .flatMap(if (_) pass else reject(UnacceptedResponseEncodingRejection(encoding)))

  /**
   * Wraps its inner Route with response compression, using the specified
   * encoders in the given order of preference.
   * If no encoders are specifically given Gzip, Deflate and NoEncoding
   * are used in this order, depending on what the client accepts.
   */
  def encodeResponse(encoders: Encoder*): Directive0 =
    theseOrDefault(encoders).map(encodeResponse).reduce(_ | _)

  /**
   * Wraps its inner Route with response compression if and only if the client
   * specifically requests compression with an `Accept-Encoding` header.
   */
  def encodeResponseIfRequested(): Directive0 = encodeResponse(NoCoding, Gzip, Deflate)

  // decoding

  /**
   * Wraps its inner Route with decoding support using the given Decoder.
   */
  def decodeRequest(decoder: Decoder): Directive0 = {
    def applyDecoder =
      extractSettings.flatMap(settings ⇒
        mapRequest(decoder.withMaxBytesPerChunk(settings.decodeMaxBytesPerChunk).decode(_).mapEntity(StreamUtils.mapEntityError {
          case NonFatal(e) ⇒
            IllegalRequestException(
              StatusCodes.BadRequest,
              ErrorInfo("The request's encoding is corrupt", e.getMessage))
        })))

    requestEntityEmpty | (
      requestEncodedWith(decoder.encoding) &
      applyDecoder &
      cancelRejections(classOf[UnsupportedRequestEncodingRejection]))
  }

  /**
   * Rejects the request with an UnsupportedRequestEncodingRejection if its encoding doesn't match the given one.
   */
  def requestEncodedWith(encoding: HttpEncoding): Directive0 =
    extract(_.request.encoding).flatMap {
      case `encoding` ⇒ pass
      case _          ⇒ reject(UnsupportedRequestEncodingRejection(encoding))
    }

  /**
   * Decompresses the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  def decodeRequest(decoders: Decoder*): Directive0 =
    theseOrDefault(decoders).map(decodeRequest).reduce(_ | _)
}

object CodingDirectives extends CodingDirectives {
  val defaultCoders: Seq[Coder] = Seq(Gzip, Deflate, NoCoding)
  def theseOrDefault[T >: Coder](these: Seq[T]): Seq[T] =
    if (these.isEmpty) defaultCoders
    else these
}
