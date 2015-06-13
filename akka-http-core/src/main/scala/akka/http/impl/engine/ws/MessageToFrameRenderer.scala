/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.util.ByteString
import akka.stream.scaladsl.{ FlattenStrategy, Source, Flow }

import Protocol.Opcode
import akka.http.scaladsl.model.ws._

/**
 * Renders messages to full frames.
 *
 * INTERNAL API
 */
private[http] object MessageToFrameRenderer {
  def create(serverSide: Boolean): Flow[Message, FrameStart, Unit] = {
    def strictFrames(opcode: Opcode, data: ByteString): Source[FrameStart, _] =
      // FIXME: fragment?
      Source.single(FrameEvent.fullFrame(opcode, None, data, fin = true))

    def streamedFrames(opcode: Opcode, data: Source[ByteString, _]): Source[FrameStart, _] =
      Source.single(FrameEvent.empty(opcode, fin = false)) ++
        data.map(FrameEvent.fullFrame(Opcode.Continuation, None, _, fin = false)) ++
        Source.single(FrameEvent.emptyLastContinuationFrame)

    Flow[Message]
      .map {
        case BinaryMessage.Strict(data) ⇒ strictFrames(Opcode.Binary, data)
        case bm: BinaryMessage          ⇒ streamedFrames(Opcode.Binary, bm.dataStream)
        case TextMessage.Strict(text)   ⇒ strictFrames(Opcode.Text, ByteString(text, "UTF-8"))
        case tm: TextMessage            ⇒ streamedFrames(Opcode.Text, tm.textStream.transform(() ⇒ new Utf8Encoder))
      }.flatten(FlattenStrategy.concat)
  }
}
