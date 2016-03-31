/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.util.ByteString
import akka.stream.scaladsl.{ Source, Flow }

import Protocol.Opcode
import akka.http.scaladsl.model.ws._

/**
 * Renders messages to full frames.
 *
 * INTERNAL API
 */
private[http] object MessageToFrameRenderer {
  def create(serverSide: Boolean): Flow[Message, FrameStart, NotUsed] = {
    def strictFrames(opcode: Opcode, data: ByteString): Source[FrameStart, _] =
      // FIXME: fragment?
      Source.single(FrameEvent.fullFrame(opcode, None, data, fin = true))

    def streamedFrames[M](opcode: Opcode, data: Source[ByteString, M]): Source[FrameStart, NotUsed] =
      Source.single(FrameEvent.empty(opcode, fin = false)) ++
        data.map(FrameEvent.fullFrame(Opcode.Continuation, None, _, fin = false)) ++
        Source.single(FrameEvent.emptyLastContinuationFrame)

    Flow[Message]
      .flatMapConcat {
        case BinaryMessage.Strict(data) ⇒ strictFrames(Opcode.Binary, data)
        case bm: BinaryMessage          ⇒ streamedFrames(Opcode.Binary, bm.dataStream)
        case TextMessage.Strict(text)   ⇒ strictFrames(Opcode.Text, ByteString(text, "UTF-8"))
        case tm: TextMessage            ⇒ streamedFrames(Opcode.Text, tm.textStream.via(Utf8Encoder))
      }
  }
}
