/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.collection.immutable
import scala.util.control.NonFatal
import akka.http.scaladsl.model.headers.{ HttpEncoding, HttpEncodings }
import akka.http.scaladsl.model._
import akka.http.scaladsl.coding._
import akka.http.impl.util._
import akka.stream.impl.fusing.GraphStages
import akka.stream.scaladsl.Flow
import akka.util.ByteString

/**
 * @groupname coding Coding directives
 * @groupprio coding 50
 */
trait CodingDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._
  import CodingDirectives._

  // encoding

  /**
   * Rejects the request with an UnacceptedResponseEncodingRejection
   * if the given response encoding is not accepted by the client.
   *
   * @group coding
   */
  def responseEncodingAccepted(encoding: HttpEncoding): Directive0 =
    extractRequest.flatMap { request ⇒
      if (EncodingNegotiator(request.headers).isAccepted(encoding)) pass
      else reject(UnacceptedResponseEncodingRejection(Set(encoding)))
    }

  /**
   * Encodes the response with the encoding that is requested by the client via the `Accept-
   * Encoding` header. The response encoding is determined by the rules specified in
   * http://tools.ietf.org/html/rfc7231#section-5.3.4.
   *
   * If the `Accept-Encoding` header is missing or empty or specifies an encoding other than
   * identity, gzip or deflate then no encoding is used.
   *
   * @group coding
   */
  def encodeResponse: Directive0 =
    _encodeResponse(DefaultEncodeResponseEncoders)

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
   * @group coding
   */
  def encodeResponseWith(first: Encoder, more: Encoder*): Directive0 =
    _encodeResponse(immutable.Seq(first +: more: _*))

  // decoding

  /**
   * Decodes the incoming request using the given Decoder.
   * If the request encoding doesn't match the request is rejected with an `UnsupportedRequestEncodingRejection`.
   *
   * @group coding
   */
  def decodeRequestWith(decoder: Decoder): Directive0 = {
    def applyDecoder =
      if (decoder == NoCoding) pass
      else
        extractSettings flatMap { settings ⇒
          val effectiveDecoder = decoder.withMaxBytesPerChunk(settings.decodeMaxBytesPerChunk)
          mapRequest { request ⇒
            effectiveDecoder.decode(request).mapEntity { entity ⇒
              entity.transformDataBytes(Flow[ByteString].recover {
                case NonFatal(e) ⇒
                  throw IllegalRequestException(
                    StatusCodes.BadRequest,
                    ErrorInfo("The request's encoding is corrupt", e.getMessage))
              })
            }
          }
        }

    requestEntityEmpty | (
      requestEncodedWith(decoder.encoding) &
      applyDecoder &
      cancelRejections(classOf[UnsupportedRequestEncodingRejection]))
  }

  /**
   * Rejects the request with an UnsupportedRequestEncodingRejection if its encoding doesn't match the given one.
   *
   * @group coding
   */
  def requestEncodedWith(encoding: HttpEncoding): Directive0 =
    extract(_.request.encoding).flatMap {
      case `encoding` ⇒ pass
      case _          ⇒ reject(UnsupportedRequestEncodingRejection(encoding))
    }

  /**
   * Decodes the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   * If no decoders are given the default encoders (`Gzip`, `Deflate`, `NoCoding`) are used.
   *
   * @group coding
   */
  def decodeRequestWith(decoders: Decoder*): Directive0 =
    theseOrDefault(decoders).map(decodeRequestWith).reduce(_ | _)

  /**
   * Decompresses the incoming request if it is `gzip` or `deflate` compressed.
   * Uncompressed requests are passed through untouched.
   * If the request encoded with another encoding the request is rejected with an `UnsupportedRequestEncodingRejection`.
   *
   * @group coding
   */
  def decodeRequest: Directive0 =
    decodeRequestWith(DefaultCoders: _*)

  /**
   * Inspects the response entity and adds a `Content-Encoding: gzip` response header if
   * the entity's media-type is precompressed with gzip and no `Content-Encoding` header is present yet.
   *
   * @group coding
   */
  def withPrecompressedMediaTypeSupport: Directive0 =
    mapResponse { response ⇒
      if (response.entity.contentType.mediaType.comp != MediaType.Gzipped) response
      else response.withDefaultHeaders(headers.`Content-Encoding`(HttpEncodings.gzip))
    }
}

object CodingDirectives extends CodingDirectives {
  val DefaultCoders: immutable.Seq[Coder] = immutable.Seq(Gzip, Deflate, NoCoding)

  private[http] val DefaultEncodeResponseEncoders = immutable.Seq(NoCoding, Gzip, Deflate)

  def theseOrDefault[T >: Coder](these: Seq[T]): Seq[T] = if (these.isEmpty) DefaultCoders else these

  import BasicDirectives._
  import RouteDirectives._

  private def _encodeResponse(encoders: immutable.Seq[Encoder]): Directive0 =
    BasicDirectives.extractRequest.flatMap { request ⇒
      val negotiator = EncodingNegotiator(request.headers)
      val encodings: List[HttpEncoding] = encoders.map(_.encoding)(collection.breakOut)
      val bestEncoder = negotiator.pickEncoding(encodings).flatMap(be ⇒ encoders.find(_.encoding == be))
      bestEncoder match {
        case Some(encoder) ⇒ mapResponse(encoder.encode(_))
        case _ ⇒
          if (encoders.contains(NoCoding) && !negotiator.hasMatchingFor(HttpEncodings.identity)) pass
          else reject(UnacceptedResponseEncodingRejection(encodings.toSet))
      }
    }
}
