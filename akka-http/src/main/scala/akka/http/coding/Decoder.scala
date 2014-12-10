/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.http.util.StreamUtils
import akka.stream.stage.Stage
import akka.util.ByteString
import headers.HttpEncoding
import akka.stream.scaladsl.Flow

trait Decoder {
  def encoding: HttpEncoding

  def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self =
    if (message.headers exists Encoder.isContentEncodingHeader)
      decodeData(message).withHeaders(message.headers filterNot Encoder.isContentEncodingHeader)
    else message.self

  def decodeData[T](t: T)(implicit mapper: DataMapper[T]): T = mapper.transformDataBytes(t, decoderFlow)

  def maxBytesPerChunk: Int
  def withMaxBytesPerChunk(maxBytesPerChunk: Int): Decoder

  def decoderFlow: Flow[ByteString, ByteString]
  def decode(input: ByteString): ByteString
}
object Decoder {
  val MaxBytesPerChunkDefault: Int = 65536
}

/** A decoder that is implemented in terms of a [[Stage]] */
trait StreamDecoder extends Decoder { outer ⇒
  protected def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ Stage[ByteString, ByteString]

  def maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault
  def withMaxBytesPerChunk(newMaxBytesPerChunk: Int): Decoder =
    new StreamDecoder {
      def encoding: HttpEncoding = outer.encoding
      override def maxBytesPerChunk: Int = newMaxBytesPerChunk

      def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ Stage[ByteString, ByteString] =
        outer.newDecompressorStage(maxBytesPerChunk)
    }

  def decoderFlow: Flow[ByteString, ByteString] =
    Flow[ByteString].transform(newDecompressorStage(maxBytesPerChunk))

  def decode(input: ByteString): ByteString = decodeWithLimits(input)
  def decodeWithLimits(input: ByteString, maxBytesSize: Int = Int.MaxValue, maxIterations: Int = 1000): ByteString =
    StreamUtils.runStrict(input, decoderFlow, maxBytesSize, maxIterations).get.get
  def decodeFromIterator(input: Iterator[ByteString], maxBytesSize: Int = Int.MaxValue, maxIterations: Int = 1000): ByteString =
    StreamUtils.runStrict(input, decoderFlow, maxBytesSize, maxIterations).get.get
}
