/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.Attributes
import akka.stream.impl.JsonObjectParser
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import akka.util.ByteString

import scala.util.control.NonFatal

/** Provides JSON framing operators that can separate valid JSON objects from incoming [[ByteString]] objects. */
object JsonFraming {

  /**
   * Returns a Flow that implements a "brace counting" based framing operator for emitting valid JSON chunks.
   * It scans the incoming data stream for valid JSON objects and returns chunks of ByteStrings containing only those valid chunks.
   *
   * Typical examples of data that one may want to frame using this operator include:
   *
   * **Very large arrays**:
   * {{{
   *   [{"id": 1}, {"id": 2}, [...], {"id": 999}]
   * }}}
   *
   * **Multiple concatenated JSON objects** (with, or without commas between them):
   *
   * {{{
   *   {"id": 1}, {"id": 2}, [...], {"id": 999}
   * }}}
   *
   * The framing works independently of formatting, i.e. it will still emit valid JSON elements even if two
   * elements are separated by multiple newlines or other whitespace characters. And of course is insensitive
   * (and does not impact the emitting frame) to the JSON object's internal formatting.
   *
   * @param maximumObjectLength The maximum length of allowed frames while decoding. If the maximum length is exceeded
   *                            this Flow will fail the stream.
   */
  def objectScanner(maximumObjectLength: Int): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new SimpleLinearGraphStage[ByteString] {

      override protected def initialAttributes: Attributes = Attributes.name("JsonFraming.objectScanner")

      override def createLogic(inheritedAttributes: Attributes) =
        new GraphStageLogic(shape) with InHandler with OutHandler {
          private val buffer = new JsonObjectParser(maximumObjectLength)

          setHandlers(in, out, this)

          override def onPush(): Unit = {
            buffer.offer(grab(in))
            tryPopBuffer()
          }

          override def onPull(): Unit =
            tryPopBuffer()

          override def onUpstreamFinish(): Unit = {
            buffer.poll() match {
              case Some(json) => emit(out, json)
              case _          => completeStage()
            }
          }

          def tryPopBuffer() = {
            try buffer.poll() match {
              case Some(json) => push(out, json)
              case _          => if (isClosed(in)) completeStage() else pull(in)
            } catch {
              case NonFatal(ex) => failStage(ex)
            }
          }
        }
    })

}
