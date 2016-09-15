/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import Protocol._
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
        def parse(reader: ByteReader): ParseResult[FrameEvent] =
          if (reader.remainingSize < 24) throw NeedMoreData
          else if (reader.take(24) == Protocol.ConnectionPreface)
            ParseResult(None, ReadFrame, false)
          else
            throw new RuntimeException("Expected ConnectionPreface!")

      }

      object ReadFrame extends Step {
        def parse(reader: ByteReader): ParseResult[FrameEvent] = {
          val length = reader.readShortBE() << 8 | reader.readByte()
          val tpe = reader.readByte() // TODO: make sure it's valid
          val flags = reader.readByte()
          val streamId = reader.readIntBE()
          // TODO: assert that reserved bit is 0 by checking if streamId > 0
          val payload = reader.take(length)
          val frame = parseFrame(FrameType.byId(tpe), flags, streamId, new ByteReader(payload))

          ParseResult(Some(frame), ReadFrame, true)
        }
      }
    }

  def parseFrame(tpe: FrameType, flags: Int, streamId: Int, payload: ByteReader): FrameEvent = {
    def isSet(flag: Int): Boolean = (flags & flag) != 0

    // TODO: add @switch? seems non-trivial for now
    tpe match {
      case HEADERS ⇒
        val pad = isSet(Flags.PADDED)
        val endStream = isSet(Flags.END_STREAM)
        val endHeaders = isSet(Flags.END_HEADERS)
        val priority = isSet(Flags.PRIORITY)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0
        val dependencyAndE =
          if (priority) payload.readLongBE()
          else 0
        val weight =
          if (priority) payload.readByte() & 0xff
          else 0

        // TODO: check that streamId != 0
        // TODO: also write out Priority frame if priority was set
        HeadersFrame(streamId, endStream, endHeaders, payload.take(payload.remainingSize - paddingLength))

      case DATA ⇒
        val pad = isSet(Flags.PADDED)
        val endStream = isSet(Flags.END_STREAM)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        DataFrame(streamId, endStream, payload.take(payload.remainingSize - paddingLength))

      case SETTINGS ⇒
        val ack = isSet(Flags.ACK)

        // TODO: validate that streamId = 0

        if (ack) SettingsAckFrame // TODO: validate that payload is empty
        else {
          def readSettings(read: List[Setting]): Seq[Setting] =
            if (payload.hasRemaining) {
              // TODO: fail if remaining size isn't exactly 3
              val id = payload.readShortBE()
              val value = payload.readIntBE()
              Setting(SettingIdentifier.byId(id), value) :: read
            } else read.reverse

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
        val endHeaders = isSet(Flags.END_HEADERS)
        // TODO: check that streamId > 0

        ContinuationFrame(streamId, endHeaders, payload.remainingData)

      case tpe ⇒ // TODO: remove once all stream types are defined
        UnknownFrameEvent(tpe, flags, streamId, payload.remainingData)
    }
  }
}
