/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing
package directives

import scala.collection.immutable

import akka.http.util._
import akka.http.model._
import akka.stream.FlowMaterializer
import headers.HttpEncoding
import akka.http.encoding._
import akka.actor.ActorRefFactory

import scala.util._

trait EncodingDirectives {
  import BasicDirectives._
  import MiscDirectives._
  import RouteDirectives._

  // encoding

  /**
   * Wraps its inner Route with encoding support using the given Encoder.
   */
  def encodeResponse(magnet: EncodeResponseMagnet): Directive0 = {
    import magnet._
    responseEncodingAccepted(encoder.encoding) &
      mapHttpResponse(encoder.encode(_, magnet.materializer)) &
      cancelAllRejections(ofType[UnacceptedResponseEncodingRejection])
  }

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
  def compressResponse(magnet: CompressResponseMagnet): Directive0 = {
    import magnet._
    encoders.tail.foldLeft(encodeResponse(encoders.head)) { (r, encoder) ⇒ r | encodeResponse(encoder) }
  }

  /**
   * Wraps its inner Route with response compression if and only if the client
   * specifically requests compression with an `Accept-Encoding` header.
   */
  def compressResponseIfRequested(magnet: RefFactoryMagnet): Directive0 = {
    import magnet._
    compressResponse(NoEncoding, Gzip, Deflate)
  }

  // decoding

  /**
   * Wraps its inner Route with decoding support using the given Decoder.
   */
  def decodeRequest(magnet: DecodeRequestMagnet): Directive0 = {
    import magnet._
    def applyDecoder = mapInnerRoute { inner ⇒
      ctx ⇒
        Try(decoder.decode(ctx.request, magnet.materializer)) match {
          case Success(decodedRequest) ⇒ inner(ctx.withRequest(decodedRequest))
          case Failure(error)          ⇒ ctx.reject(CorruptRequestEncodingRejection(error.getMessage.nullAsEmpty))
        }
    }
    requestEntityEmpty | (
      requestEncodedWith(decoder.encoding) &
      applyDecoder &
      cancelAllRejections(ofTypes(classOf[UnsupportedRequestEncodingRejection], classOf[CorruptRequestEncodingRejection])))
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
   * Decompresses the incoming request if it is GZip or Deflate encoded.
   * Uncompressed requests are passed on to the inner route unchanged.
   */
  def decompressRequest(magnet: RefFactoryMagnet): Directive0 = {
    import magnet.materializer
    decompressRequest(Gzip, Deflate, NoEncoding)
  }

  /**
   * Decompresses the incoming request if it is encoded with one of the given
   * encoders. If the request encoding doesn't match one of the given encoders
   * the request is rejected with an `UnsupportedRequestEncodingRejection`.
   */
  def decompressRequest(magnet: DecompressRequestMagnet): Directive0 = {
    import magnet._
    if (more.isEmpty) decodeRequest(first)
    else more.foldLeft(decodeRequest(first)) { (r, decoder) ⇒ r | decodeRequest(decoder) }
  }
}

object EncodingDirectives extends EncodingDirectives

class EncodeResponseMagnet(val encoder: Encoder, val autoChunkThreshold: Long = 128 * 1024,
                           val autoChunkSize: Int = 128 * 1024)(implicit val refFactory: ActorRefFactory, val materializer: FlowMaterializer)
object EncodeResponseMagnet {
  implicit def fromEncoder(encoder: Encoder)(implicit factory: ActorRefFactory, materializer: FlowMaterializer): EncodeResponseMagnet = // # EncodeResponseMagnet
    new EncodeResponseMagnet(encoder)
  implicit def fromEncoderThresholdAndChunkSize(t: (Encoder, Long, Int))(implicit factory: ActorRefFactory, materializer: FlowMaterializer): EncodeResponseMagnet = // # EncodeResponseMagnet
    new EncodeResponseMagnet(t._1, t._2, t._3)
}

class CompressResponseMagnet(val encoders: List[Encoder])(implicit val refFactory: ActorRefFactory, val materializer: FlowMaterializer)
object CompressResponseMagnet {
  implicit def fromUnit(u: Unit)(implicit refFactory: ActorRefFactory, materializer: FlowMaterializer): CompressResponseMagnet =
    new CompressResponseMagnet(Gzip :: Deflate :: NoEncoding :: Nil)
  implicit def fromEncoders1(e: Encoder)(implicit refFactory: ActorRefFactory, materializer: FlowMaterializer): CompressResponseMagnet =
    new CompressResponseMagnet(e :: Nil)
  implicit def fromEncoders2(t: (Encoder, Encoder))(implicit refFactory: ActorRefFactory, materializer: FlowMaterializer): CompressResponseMagnet =
    new CompressResponseMagnet(t._1 :: t._2 :: Nil)
  implicit def fromEncoders3(t: (Encoder, Encoder, Encoder))(implicit refFactory: ActorRefFactory, materializer: FlowMaterializer): CompressResponseMagnet =
    new CompressResponseMagnet(t._1 :: t._2 :: t._3 :: Nil)
}

class RefFactoryMagnet(implicit val refFactory: ActorRefFactory, val materializer: FlowMaterializer)
object RefFactoryMagnet {
  implicit def fromUnit(u: Unit)(implicit refFactory: ActorRefFactory, materializer: FlowMaterializer): RefFactoryMagnet = new RefFactoryMagnet
}

class DecodeRequestMagnet(val decoder: Decoder)(implicit val materializer: FlowMaterializer)
object DecodeRequestMagnet {
  implicit def fromDecoder(decoder: Decoder)(implicit materializer: FlowMaterializer): DecodeRequestMagnet =
    new DecodeRequestMagnet(decoder)
}

class DecompressRequestMagnet(val first: Decoder, val more: immutable.Seq[Decoder])(implicit val materializer: FlowMaterializer)
object DecompressRequestMagnet {
  implicit def fromDecoders1(decoder: Decoder)(implicit materializer: FlowMaterializer): DecompressRequestMagnet =
    new DecompressRequestMagnet(decoder, Nil)
  implicit def fromDecoders2(decoders: (Decoder, Decoder))(implicit materializer: FlowMaterializer): DecompressRequestMagnet =
    new DecompressRequestMagnet(decoders._1, List(decoders._2))
  implicit def fromDecoders3(decoders: (Decoder, Decoder, Decoder))(implicit materializer: FlowMaterializer): DecompressRequestMagnet =
    new DecompressRequestMagnet(decoders._1, List(decoders._2, decoders._3))
}