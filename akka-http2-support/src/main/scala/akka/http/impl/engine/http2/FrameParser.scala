/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import Http2Protocol._
import FrameType._
import akka.stream.Attributes
import akka.stream.impl.io.ByteStringParser
import akka.stream.stage.GraphStageLogic

class FrameParser(shouldReadPreface: Boolean) extends ByteStringParser[FrameEvent] {
  import ByteStringParser._

  abstract class Step extends ParseStep[FrameEvent]

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new ParsingLogic {
      startWith {
        if (shouldReadPreface) ReadPreface
        else ReadFrame
      }

      object ReadPreface extends Step {
        override def parse(reader: ByteReader): ParseResult[FrameEvent] =
          if (reader.remainingSize < 24) throw NeedMoreData
          else if (reader.take(24) == Http2Protocol.ClientConnectionPreface)
            ParseResult(None, ReadFrame, acceptUpstreamFinish = false)
          else
            throw new RuntimeException("Expected ConnectionPreface!")
      }

      object ReadFrame extends Step {
        override def parse(reader: ByteReader): ParseResult[FrameEvent] = {
          val length = reader.readShortBE() << 8 | reader.readByte()
          val tpe = reader.readByte() // TODO: make sure it's valid
          val flags = new ByteFlag(reader.readByte())
          val streamId = reader.readIntBE()
          // TODO: assert that reserved bit is 0 by checking if streamId > 0
          val payload = reader.take(length)
          val frame = parseFrame(FrameType.byId(tpe), flags, streamId, new ByteReader(payload))

          ParseResult(Some(frame), ReadFrame, acceptUpstreamFinish = true)
        }
      }
    }

  def parseFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteReader): FrameEvent = {
    // TODO: add @switch? seems non-trivial for now
    tpe match {
      case GOAWAY ⇒
        Http2Compliance.requireZeroStreamId(streamId)
        GoAwayFrame(payload.readIntBE(), ErrorCode.byId(payload.readIntBE()), payload.takeAll())

      case HEADERS ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)
        val endHeaders = Flags.END_HEADERS.isSet(flags)
        val priority = Flags.PRIORITY.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0
        val dependencyAndE =
          if (priority) payload.readIntBE()
          else 0
        val weight =
          if (priority) payload.readByte() & 0xff
          else 0

        val exclusiveFlag = (dependencyAndE >>> 31) == 1 // most significant bit for exclusive flag
        val dependencyId = dependencyAndE & 0x7fffffff // remaining 31 bits for the dependency part

        Http2Compliance.requireNoSelfDependency(streamId, dependencyId)

        // TODO: also write out Priority frame if priority was set
        HeadersFrame(streamId, endStream, endHeaders, payload.take(payload.remainingSize - paddingLength))

      case DATA ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        DataFrame(streamId, endStream, payload.take(payload.remainingSize - paddingLength))

      case SETTINGS ⇒
        val ack = Flags.ACK.isSet(flags)
        Http2Compliance.requireZeroStreamId(streamId)

        if (ack) {
          // validate that payload is empty: (6.5)
          if (payload.hasRemaining)
            throw new Http2Compliance.IllegalPayloadInSettingsAckFrame(payload.remainingSize, s"SETTINGS ACK frame MUST NOT contain payload (spec 6.5)!")

          SettingsAckFrame
        } else {
          def readSettings(read: List[Setting]): Seq[Setting] =
            if (payload.hasRemaining) {
              val id = payload.readShortBE()
              val value = payload.readIntBE()
              readSettings(Setting(SettingIdentifier.byId(id), value) :: read)
            } else read.reverse

          if (payload.remainingSize % 6 != 0) throw new Http2Compliance.IllegalPayloadLengthInSettingsFrame(payload.remainingSize, "SETTINGS payload MUDT be a multiple of multiple of 6 octets")
          SettingsFrame(readSettings(Nil))
        }

      case WINDOW_UPDATE ⇒
        // TODO: check frame size
        // TODO: check flags
        // TODO: check reserved flag
        // TODO: check that increment is > 0
        val increment = payload.readIntBE()
        WindowUpdateFrame(streamId, increment)

      case CONTINUATION ⇒
        val endHeaders = Flags.END_HEADERS.isSet(flags)

        ContinuationFrame(streamId, endHeaders, payload.remainingData)

      case PING ⇒
        Http2Compliance.requireFrameSize(payload.remainingSize, 8)
        Http2Compliance.requireZeroStreamId(streamId)
        val ack = Flags.ACK.isSet(flags)
        PingFrame(ack, payload.remainingData)

      case RST_STREAM ⇒
        Http2Compliance.requireFrameSize(payload.remainingSize, 4)
        RstStreamFrame(streamId, ErrorCode.byId(payload.readIntBE()))

      case PRIORITY ⇒
        Http2Compliance.requireFrameSize(payload.remainingSize, 5)
        val streamDependency = payload.readIntBE() // whole word
        val exclusiveFlag = (streamDependency >>> 31) == 1 // most significant bit for exclusive flag
        val dependencyId = streamDependency & 0x7fffffff // remaining 31 bits for the dependency part
        val priority = payload.readByte() & 0xff
        Http2Compliance.requireNoSelfDependency(streamId, dependencyId)
        PriorityFrame(streamId, exclusiveFlag, dependencyId, priority)

      case tpe ⇒ // TODO: remove once all stream types are defined
        UnknownFrameEvent(tpe, flags, streamId, payload.remainingData)
    }
  }
}
