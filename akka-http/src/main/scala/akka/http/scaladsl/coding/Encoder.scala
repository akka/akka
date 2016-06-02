/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.http.impl.util.StreamUtils
import akka.stream.FlowShape
import akka.stream.stage.GraphStage
import akka.util.ByteString
import headers._
import akka.stream.scaladsl.Flow

trait Encoder {
  def encoding: HttpEncoding

  def messageFilter: HttpMessage ⇒ Boolean

  def encode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self =
    if (messageFilter(message) && !message.headers.exists(Encoder.isContentEncodingHeader))
      encodeData(message).withHeaders(`Content-Encoding`(encoding) +: message.headers)
    else message.self

  def encodeData[T](t: T)(implicit mapper: DataMapper[T]): T =
    mapper.transformDataBytes(t, Flow[ByteString].via(newEncodeTransformer))

  def encode(input: ByteString): ByteString = newCompressor.compressAndFinish(input)

  def encoderFlow: Flow[ByteString, ByteString, NotUsed] = Flow[ByteString].via(newEncodeTransformer)

  def newCompressor: Compressor

  def newEncodeTransformer(): GraphStage[FlowShape[ByteString, ByteString]] = {
    val compressor = newCompressor

    def encodeChunk(bytes: ByteString): ByteString = compressor.compressAndFlush(bytes)
    def finish(): ByteString = compressor.finish()

    StreamUtils.byteStringTransformer(encodeChunk, finish)
  }
}

object Encoder {
  val DefaultFilter: HttpMessage ⇒ Boolean = isCompressible _
  private[coding] def isCompressible(msg: HttpMessage): Boolean =
    msg.entity.contentType.mediaType.isCompressible

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
