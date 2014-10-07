/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.ByteArrayOutputStream
import akka.http.model._
import akka.http.util.StreamUtils
import akka.stream.Transformer
import akka.util.ByteString
import headers._

trait Encoder {
  def encoding: HttpEncoding

  def messageFilter: HttpMessage ⇒ Boolean

  def encode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self =
    if (messageFilter(message) && !message.headers.exists(Encoder.isContentEncodingHeader))
      encodeData(message).withHeaders(`Content-Encoding`(encoding) +: message.headers)
    else message.self

  def encodeData[T](t: T)(implicit mapper: DataMapper[T]): T =
    mapper.transformDataBytes(t, newEncodeTransformer)

  def newCompressor: Compressor

  def newEncodeTransformer(): Transformer[ByteString, ByteString] = {
    val compressor = newCompressor

    def encodeChunk(bytes: ByteString): ByteString = compressor.compressAndFlush(bytes)
    def finish(): ByteString = compressor.finish()

    StreamUtils.byteStringTransformer(encodeChunk, finish)
  }
}

object Encoder {
  val DefaultFilter: HttpMessage ⇒ Boolean = {
    case req: HttpRequest                    ⇒ isCompressible(req)
    case res @ HttpResponse(status, _, _, _) ⇒ isCompressible(res) && status.isSuccess
  }
  private[coding] def isCompressible(msg: HttpMessage): Boolean =
    msg.entity.contentType.mediaType.compressible

  private[coding] val isContentEncodingHeader: HttpHeader ⇒ Boolean = _.isInstanceOf[`Content-Encoding`]
}

/** A stateful object representing ongoing compression. */
abstract class Compressor {
  /**
   * Compresses the given input and returns compressed data. The implementation
   * can and will choose to buffer output data to improve compression. Use
   * `flush` or `compressAndFlush` to make sure that all input data has been
   * compressed and pending output data has been returned.
   */
  def compress(input: ByteString): ByteString

  /**
   * Flushes any output data and returns the currently remaining compressed data.
   */
  def flush(): ByteString

  /**
   * Closes this compressed stream and return the remaining compressed data. After
   * calling this method, this Compressor cannot be used any further.
   */
  def finish(): ByteString

  /** Combines `compress` + `flush` */
  def compressAndFlush(input: ByteString): ByteString
  /** Combines `compress` + `finish` */
  def compressAndFinish(input: ByteString): ByteString
}