/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io.compression

import akka.NotUsed
import akka.stream.{ Attributes, FlowShape }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString

/** INTERNAL API */
private[stream] object CompressionUtils {
  /**
   * Exposes a constructor for a (mutable) [[Compressor]] as a [[Flow[ByteString, ByteString, NotUsed]]]
   */
  def compressorToFlow(newCompressor: () ⇒ Compressor): Flow[ByteString, ByteString, NotUsed] = {
    val compressor = newCompressor()

    def encodeChunk(bytes: ByteString): ByteString = compressor.compressAndFlush(bytes)
    def finish(): ByteString = compressor.finish()

    Flow[ByteString].via(byteStringTransformer(encodeChunk, finish))
  }

  /**
   * Creates a transformer that will call `f` for each incoming ByteString and output its result. After the complete
   * input has been read it will call `finish` once to determine the final ByteString to post to the output.
   * Empty ByteStrings are discarded.
   */
  def byteStringTransformer(f: ByteString ⇒ ByteString, finish: () ⇒ ByteString): GraphStage[FlowShape[ByteString, ByteString]] = new SimpleLinearGraphStage[ByteString] {
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
      override def onPush(): Unit = {
        val data = f(grab(in))
        if (data.nonEmpty) push(out, data)
        else pull(in)
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        val data = finish()
        if (data.nonEmpty) emit(out, data)
        completeStage()
      }

      setHandlers(in, out, this)
    }
  }
}