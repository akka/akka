/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.http.impl.engine.ws.Protocol.Opcode
import akka.util.ByteString

private[http] sealed trait FrameEventOrError

private[http] final case class FrameError(p: ProtocolException) extends FrameEventOrError

/**
 * The low-level WebSocket framing model.
 *
 * INTERNAL API
 */
private[http] sealed trait FrameEvent extends FrameEventOrError {
  def data: ByteString
  def lastPart: Boolean
  def withData(data: ByteString): FrameEvent
}

/**
 * Starts a frame. Contains the frame's headers. May contain all the data of the frame if `lastPart == true`. Otherwise,
 * following events will be `FrameData` events that contain the remaining data of the frame.
 */
private[http] final case class FrameStart(header: FrameHeader, data: ByteString) extends FrameEvent {
  def lastPart: Boolean = data.size == header.length
  def withData(data: ByteString): FrameStart = copy(data = data)

  def isFullMessage: Boolean = header.fin && header.length == data.length
}

/**
 * Frame data that was received after the start of the frame..
 */
private[http] final case class FrameData(data: ByteString, lastPart: Boolean) extends FrameEvent {
  def withData(data: ByteString): FrameData = copy(data = data)
}

/** Model of the frame header */
private[http] final case class FrameHeader(
  opcode: Protocol.Opcode,
  mask:   Option[Int],
  length: Long,
  fin:    Boolean,
  rsv1:   Boolean         = false,
  rsv2:   Boolean         = false,
  rsv3:   Boolean         = false)

private[http] object FrameEvent {
  def empty(
    opcode: Protocol.Opcode,
    fin:    Boolean,
    rsv1:   Boolean         = false,
    rsv2:   Boolean         = false,
    rsv3:   Boolean         = false): FrameStart =
    fullFrame(opcode, None, ByteString.empty, fin, rsv1, rsv2, rsv3)
  def fullFrame(opcode: Protocol.Opcode, mask: Option[Int], data: ByteString,
                fin:  Boolean,
                rsv1: Boolean = false,
                rsv2: Boolean = false,
                rsv3: Boolean = false): FrameStart =
    FrameStart(FrameHeader(opcode, mask, data.length, fin, rsv1, rsv2, rsv3), data)
  val emptyLastContinuationFrame: FrameStart =
    empty(Protocol.Opcode.Continuation, fin = true)

  def closeFrame(closeCode: Int, reason: String = "", mask: Option[Int] = None): FrameStart = {
    require(closeCode >= 1000, s"Invalid close code: $closeCode")
    val body = ByteString(
      ((closeCode & 0xff00) >> 8).toByte,
      (closeCode & 0xff).toByte) ++ ByteString(reason, "UTF8")

    fullFrame(Opcode.Close, mask, FrameEventParser.mask(body, mask), fin = true)
  }
}
