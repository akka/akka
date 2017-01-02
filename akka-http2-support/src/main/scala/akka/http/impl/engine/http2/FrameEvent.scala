/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.util.ByteString

sealed trait FrameEvent
sealed trait StreamFrameEvent extends FrameEvent {
  def streamId: Int
}

final case class GoAwayFrame(lastStreamId: Int, errorCode: ErrorCode, debug: ByteString = ByteString.empty) extends FrameEvent {
  override def toString: String = s"GoAwayFrame($lastStreamId,$errorCode,debug:<hidden>)"
}
final case class DataFrame(
  streamId:  Int,
  endStream: Boolean,
  payload:   ByteString) extends StreamFrameEvent

final case class HeadersFrame(
  streamId:            Int,
  endStream:           Boolean,
  endHeaders:          Boolean,
  headerBlockFragment: ByteString) extends StreamFrameEvent

final case class RstStreamFrame(streamId: Int, errorCode: ErrorCode) extends StreamFrameEvent
final case class SettingsFrame(settings: Seq[Setting]) extends FrameEvent
case object SettingsAckFrame extends FrameEvent
//case class PushPromiseFrame(streamId: Int) extends StreamFrameEvent
case class PingFrame(ack: Boolean, data: ByteString) extends FrameEvent
final case class WindowUpdateFrame(
  streamId:            Int,
  windowSizeIncrement: Int) extends StreamFrameEvent
final case class ContinuationFrame(
  streamId:   Int,
  endHeaders: Boolean,
  payload:    ByteString) extends StreamFrameEvent

final case class PriorityFrame(
  streamId:         Int,
  exclusiveFlag:    Boolean,
  streamDependency: Int,
  weight:           Int) extends StreamFrameEvent

final case class Setting(
  identifier: SettingIdentifier,
  value:      Int)

object Setting {
  implicit def autoConvertFromTuple(tuple: (SettingIdentifier, Int)): Setting =
    Setting(tuple._1, tuple._2)
}

/** Dummy event for all unknown frames */
final case class UnknownFrameEvent(
  tpe:      FrameType,
  flags:    ByteFlag,
  streamId: Int,
  payload:  ByteString) extends StreamFrameEvent