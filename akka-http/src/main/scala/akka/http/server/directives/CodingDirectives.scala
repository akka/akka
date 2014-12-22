/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.util._
import akka.http.model._
import akka.http.model.headers.{ HttpEncodings, `Accept-Encoding`, HttpEncoding, HttpEncodingRange }
import akka.http.coding._
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal

trait CodingDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._
  import CodingDirectives._
  import HeaderDirectives._

  // encoding

  /**
   * Rejects the request with an UnacceptedResponseEncodingRejection
   * if the given encoding is not accepted for the response.
   */
  def responseEncodingAccepted(encoding: HttpEncoding): Directive0 =
    extract(_.request.isEncodingAccepted(encoding))
      .flatMap(if (_) pass else reject(UnacceptedResponseEncodingRejection(Set(encoding))))

  /**
   * Encodes the response with the encoding that is requested by the client with the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing or empty or specifies an encoding other than
   * identity, gzip or deflate then no encoding is used.
   */
  def encodeResponse = encodeResponseWith(NoCoding, Gzip, Deflate)

  /**
   * Encodes the response with the encoding that is requested by the client with the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing then the response is encoded using the `first`
   * encoder.
   *
   * If the `Accept-Encoding` header is empty and `NoCoding` is part of the encoders then no
   * response encoding is used. Otherwise the request is rejected.
   */
  def encodeResponseWith(first: Encoder, more: Encoder*) = _encodeResponse(immutable.Seq(first +: more: _*))

  private def _encodeResponse(encoders: immutable.Seq[Encoder]): Directive0 =
    optionalHeaderValueByType[`Accept-Encoding`]().flatMap { accept ⇒
      val acceptedEncoder = accept match {
        case None ⇒
          // use first defined encoder when Accept-Encoding is missing
          encoders.headOption
        case Some(`Accept-Encoding`(encodings)) ⇒
          // provide fallback to identity
          val withIdentity =
            if (encodings.exists {
              case HttpEncodingRange.One(HttpEncodings.identity, _) ⇒ true
              case _ ⇒ false
            }) encodings
            else encodings :+ HttpEncodings.`identity;q=MIN`
          // sort client-accepted encodings by q-Value (and orig. order) and find first matching encoder
          @tailrec def find(encodings: List[HttpEncodingRange]): Option[Encoder] = encodings match {
            case encoding :: rest ⇒
              encoders.find(e ⇒ encoding.matches(e.encoding)) match {
                case None ⇒ find(rest)
                case x    ⇒ x
              }
            case _ ⇒ None
          }
          find(withIdentity.sortBy(e ⇒ (-e.qValue, withIdentity.indexOf(e))).toList)
      }
      acceptedEncoder match {
        case Some(encoder) ⇒ mapResponse(encoder.encode(_))
        case _             ⇒ reject(UnacceptedResponseEncodingRejection(encoders.map(_.encoding).toSet))
      }
    }

  // decoding

  /**
   * Wraps its inner Route with decoding support using the given Decoder.
   */
  def decodeRequestWith(decoder: Decoder): Directive0 = {
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
  def decodeRequestWith(decoders: Decoder*): Directive0 =
    theseOrDefault(decoders).map(decodeRequestWith).reduce(_ | _)

  def decodeRequest: Directive0 =
    decodeRequestWith(DefaultCoders: _*)
}

object CodingDirectives extends CodingDirectives {
  val DefaultCoders: immutable.Seq[Coder] = immutable.Seq(Gzip, Deflate, NoCoding)
  def theseOrDefault[T >: Coder](these: Seq[T]): Seq[T] =
    if (these.isEmpty) DefaultCoders
    else these
}
