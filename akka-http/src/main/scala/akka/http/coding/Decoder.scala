/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.stream.FlowMaterializer
import akka.stream.stage.Stage
import akka.util.ByteString
import headers.HttpEncoding
import akka.stream.scaladsl.{ Sink, Source, Flow }

import scala.concurrent.Future

trait Decoder {
  def encoding: HttpEncoding

  def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self =
    if (message.headers exists Encoder.isContentEncodingHeader)
      decodeData(message).withHeaders(message.headers filterNot Encoder.isContentEncodingHeader)
    else message.self

  def decodeData[T](t: T)(implicit mapper: DataMapper[T]): T = mapper.transformDataBytes(t, decoderFlow)

  def maxBytesPerChunk: Int
  def withMaxBytesPerChunk(maxBytesPerChunk: Int): Decoder

  def decoderFlow: Flow[ByteString, ByteString, Unit]
  def decode(input: ByteString)(implicit mat: FlowMaterializer): Future[ByteString] =
    Source.single(input).via(decoderFlow).runWith(Sink.head)
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

  def decoderFlow: Flow[ByteString, ByteString, Unit] =
    Flow[ByteString].transform(newDecompressorStage(maxBytesPerChunk))

}
