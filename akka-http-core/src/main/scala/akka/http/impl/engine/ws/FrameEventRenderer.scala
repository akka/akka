/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.util.ByteString
import akka.stream.stage.{ StatefulStage, SyncDirective, Context }

import scala.annotation.tailrec

/**
 * Renders FrameEvents to ByteString.
 *
 * INTERNAL API
 */
private[http] class FrameEventRenderer extends StatefulStage[FrameEvent, ByteString] {
  def initial: State = Idle

  object Idle extends State {
    def onPush(elem: FrameEvent, ctx: Context[ByteString]): SyncDirective = elem match {
      case start @ FrameStart(header, data) ⇒
        require(header.length >= data.size)
        if (!start.lastPart && header.length > 0) become(renderData(header.length - data.length, this))

        ctx.push(renderStart(start))

      case f: FrameData ⇒
        ctx.fail(new IllegalStateException("unexpected FrameData (need FrameStart first)"))
    }
  }

  def renderData(initialRemaining: Long, nextState: State): State =
    new State {
      var remaining: Long = initialRemaining

      def onPush(elem: FrameEvent, ctx: Context[ByteString]): SyncDirective = elem match {
        case FrameData(data, lastPart) ⇒
          if (data.size > remaining)
            throw new IllegalStateException(s"Expected $remaining frame bytes but got ${data.size}")
          else if (data.size == remaining) {
            if (!lastPart) throw new IllegalStateException(s"Frame data complete but `lastPart` flag not set")
            become(nextState)
            ctx.push(data)
          } else {
            remaining -= data.size
            ctx.push(data)
          }

        case f: FrameStart ⇒
          ctx.fail(new IllegalStateException("unexpected FrameStart (need more FrameData first)"))
      }
    }

  def renderStart(start: FrameStart): ByteString = renderHeader(start.header) ++ start.data
  def renderHeader(header: FrameHeader): ByteString = {
    import Protocol._

    val length = header.length
    val (lengthBits, extraLengthBytes) = length match {
      case x if x < 126     ⇒ (x.toInt, 0)
      case x if x <= 0xFFFF ⇒ (126, 2)
      case _                ⇒ (127, 8)
    }

    val maskBytes = if (header.mask.isDefined) 4 else 0
    val totalSize = 2 + extraLengthBytes + maskBytes

    val data = new Array[Byte](totalSize)

    def bool(b: Boolean, mask: Int): Int = if (b) mask else 0
    val flags =
      bool(header.fin, FIN_MASK) |
        bool(header.rsv1, RSV1_MASK) |
        bool(header.rsv2, RSV2_MASK) |
        bool(header.rsv3, RSV3_MASK)

    data(0) = (flags | header.opcode.code).toByte
    data(1) = (bool(header.mask.isDefined, MASK_MASK) | lengthBits).toByte

    extraLengthBytes match {
      case 0 ⇒
      case 2 ⇒
        data(2) = ((length & 0xFF00) >> 8).toByte
        data(3) = ((length & 0x00FF) >> 0).toByte
      case 8 ⇒
        @tailrec def addLongBytes(l: Long, writtenBytes: Int): Unit =
          if (writtenBytes < 8) {
            data(2 + writtenBytes) = (l & 0xff).toByte
            addLongBytes(java.lang.Long.rotateLeft(l, 8), writtenBytes + 1)
          }

        addLongBytes(java.lang.Long.rotateLeft(length, 8), 0)
    }

    val maskOffset = 2 + extraLengthBytes
    header.mask.foreach { mask ⇒
      data(maskOffset + 0) = ((mask & 0xFF000000) >> 24).toByte
      data(maskOffset + 1) = ((mask & 0x00FF0000) >> 16).toByte
      data(maskOffset + 2) = ((mask & 0x0000FF00) >> 8).toByte
      data(maskOffset + 3) = ((mask & 0x000000FF) >> 0).toByte
    }

    ByteString(data)
  }
}
