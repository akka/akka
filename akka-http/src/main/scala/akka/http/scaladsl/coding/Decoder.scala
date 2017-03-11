/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream.{ FlowShape, Materializer }
import akka.stream.stage.GraphStage
import akka.util.ByteString
import headers.HttpEncoding
import akka.stream.scaladsl.{ Flow, Sink, Source }

import scala.concurrent.Future

trait Decoder {
  def encoding: HttpEncoding

  def decodeMessage(message: HttpMessage): HttpMessage#Self =
    if (message.headers exists Encoder.isContentEncodingHeader)
      message.transformEntityDataBytes(decoderFlow).withHeaders(message.headers filterNot Encoder.isContentEncodingHeader)
    else message.self

  def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self =
    decodeMessage(message).asInstanceOf[T#Self]

  def decodeData[T](t: T)(implicit mapper: DataMapper[T]): T = mapper.transformDataBytes(t, decoderFlow)

  def maxBytesPerChunk: Int
  def withMaxBytesPerChunk(maxBytesPerChunk: Int): Decoder

  def decoderFlow: Flow[ByteString, ByteString, NotUsed]
  def decode(input: ByteString)(implicit mat: Materializer): Future[ByteString] =
    Source.single(input).via(decoderFlow).runWith(Sink.fold(ByteString.empty)(_ ++ _))
}
object Decoder {
  val MaxBytesPerChunkDefault: Int = 65536
}

/** A decoder that is implemented in terms of a [[Stage]] */
trait StreamDecoder extends Decoder { outer ⇒
  protected def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ GraphStage[FlowShape[ByteString, ByteString]]

  def maxBytesPerChunk: Int = Decoder.MaxBytesPerChunkDefault
  def withMaxBytesPerChunk(newMaxBytesPerChunk: Int): Decoder =
    new StreamDecoder {
      def encoding: HttpEncoding = outer.encoding
      override def maxBytesPerChunk: Int = newMaxBytesPerChunk

      def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ GraphStage[FlowShape[ByteString, ByteString]] =
        outer.newDecompressorStage(maxBytesPerChunk)
    }

  def decoderFlow: Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(newDecompressorStage(maxBytesPerChunk)())

}
