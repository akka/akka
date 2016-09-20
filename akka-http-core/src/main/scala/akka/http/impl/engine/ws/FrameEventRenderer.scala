/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.stream.stage._
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.util.ByteString

import scala.annotation.tailrec

/**
 * Renders FrameEvents to ByteString.
 *
 * INTERNAL API
 */
private[http] final class FrameEventRenderer extends GraphStage[FlowShape[FrameEvent, ByteString]] {
  val in = Inlet[FrameEvent]("in")
  val out = Outlet[ByteString]("out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    val Initial = new InHandler {
      override def onPush(): Unit = grab(in) match {
        case start @ FrameStart(header, data) ⇒
          require(header.length >= data.size)
          if (!start.lastPart && header.length > 0)
            setHandler(in, renderData(header.length - data.length, this))

          push(out, renderStart(start))

        case f: FrameData ⇒
          fail(out, new IllegalStateException("unexpected FrameData (need FrameStart first)"))
      }
    }

    def renderData(initialRemaining: Long, nextState: InHandler): InHandler =
      new InHandler {
        var remaining: Long = initialRemaining

        override def onPush(): Unit = {
          grab(in) match {
            case FrameData(data, lastPart) ⇒
              if (data.size > remaining)
                throw new IllegalStateException(s"Expected $remaining frame bytes but got ${data.size}")
              else if (data.size == remaining) {
                if (!lastPart) throw new IllegalStateException(s"Frame data complete but `lastPart` flag not set")
                setHandler(in, nextState)
                push(out, data)
              } else {
                remaining -= data.size
                push(out, data)
              }

            case f: FrameStart ⇒
              fail(out, new IllegalStateException("unexpected FrameStart (need more FrameData first)"))
          }
        }
      }

    setHandler(in, Initial)
    setHandler(out, new OutHandler {
      override def onPull(): Unit = pull(in)
    })
  }

  private def renderStart(start: FrameStart): ByteString = renderHeader(start.header) ++ start.data

  private def renderHeader(header: FrameHeader): ByteString = {
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
