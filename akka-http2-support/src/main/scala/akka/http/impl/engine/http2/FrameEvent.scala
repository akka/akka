/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.Http2Protocol.SettingIdentifier
import akka.util.ByteString

sealed trait FrameEvent {
  def flags: ByteFlag
  def hasFlag(f: ByteFlag): Boolean = f.isSet(flags)
}
sealed trait StreamFrameEvent extends FrameEvent {
  def streamId: Int
}

final case class DataFrame(
  flags:    ByteFlag,
  streamId: Int,
  payload:  ByteString) extends StreamFrameEvent {
  def endStream = Http2Protocol.Flags.END_STREAM.isSet(flags)
}

final case class HeadersFrame(
  flags:               ByteFlag,
  streamId:            Int,
  headerBlockFragment: ByteString) extends StreamFrameEvent {
  def endStream = Http2Protocol.Flags.END_STREAM.isSet(flags)
  def endHeaders = Http2Protocol.Flags.END_HEADERS.isSet(flags)
}

//final case class PriorityFrame(streamId: Int, streamDependency: Int, weight: Int) extends StreamFrameEvent
//final case class RstStreamFrame(streamId: Int, errorCode: Int) extends StreamFrameEvent
final case class SettingsFrame(
  flags:    ByteFlag,
  settings: Seq[Setting]) extends FrameEvent {
}
case object SettingsAckFrame extends FrameEvent {
  override val flags: ByteFlag = ByteFlag.Zero
}
//case class PushPromiseFrame(streamId: Int) extends StreamFrameEvent
//case class PingFrame(streamId: Int) extends StreamFrameEvent
//case class GoAwayFrame(streamId: Int) extends StreamFrameEvent
final case class WindowUpdateFrame(
  flags:               ByteFlag,
  streamId:            Int,
  windowSizeIncrement: Int) extends StreamFrameEvent {
}
final case class ContinuationFrame(
  flags:    ByteFlag,
  streamId: Int, endHeaders: Boolean, payload: ByteString) extends StreamFrameEvent {
}

final case class Setting(identifier: SettingIdentifier, value: Int)

/** Dummy event for all unknown frames */
final case class UnknownFrameEvent(
  flags:    ByteFlag,
  tpe:      FrameType,
  streamId: Int,
  payload:  ByteString) extends StreamFrameEvent {

}
