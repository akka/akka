package akka.http.impl.http2

import akka.http.impl.http2.Protocol.FrameType
import akka.http.impl.http2.Protocol.SettingIdentifier
import akka.util.ByteString

sealed trait FrameEvent
sealed trait StreamFrameEvent extends FrameEvent {
  def streamId: Int
}

case class GenericEvent(tpe: FrameType, flags: Int, streamId: Int, payload: ByteString) extends StreamFrameEvent

case class DataFrame(streamId: Int /* TODO: finish */ ) extends StreamFrameEvent
case class HeadersFrame(
  streamId:            Int,
  endStream:           Boolean,
  endHeaders:          Boolean,
  headerBlockFragment: ByteString) extends FrameEvent

//case class PriorityFrame(streamId: Int, streamDependency: Int, weight: Int) extends StreamFrameEvent
//case class RstStreamFrame(streamId: Int, errorCode: Int) extends StreamFrameEvent
case class SettingsFrame(settings: Seq[Setting]) extends FrameEvent
case object SettingsAckFrame extends FrameEvent
//case class PushPromiseFrame(streamId: Int) extends StreamFrameEvent
//case class PingFrame(streamId: Int) extends StreamFrameEvent
//case class GoAwayFrame(streamId: Int) extends StreamFrameEvent
case class WindowUpdateFrame(streamId: Int, windowSizeIncrement: Int) extends StreamFrameEvent
case class ContinuationFrame(streamId: Int, endHeaders: Boolean, payload: ByteString) extends StreamFrameEvent

case class Setting(identifier: SettingIdentifier, value: Int)