/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
        def parse(reader: ByteReader): ParseResult[FrameEvent] =
          if (reader.remainingSize < 24) throw NeedMoreData
          else if (reader.take(24) == Http2Protocol.ConnectionPreface)
            ParseResult(None, ReadFrame, false)
          else
            throw new RuntimeException("Expected ConnectionPreface!")
      }

      object ReadFrame extends Step {
        def parse(reader: ByteReader): ParseResult[FrameEvent] = {
          val length = reader.readShortBE() << 8 | reader.readByte()
          val tpe = reader.readByte() // TODO: make sure it's valid
          val flags = new ByteFlag(reader.readByte().toByte)
          val streamId = reader.readIntBE()
          // TODO: assert that reserved bit is 0 by checking if streamId > 0
          val payload = reader.take(length)
          val frame = parseFrame(FrameType.byId(tpe), flags, streamId, new ByteReader(payload))

          ParseResult(Some(frame), ReadFrame, true)
        }
      }
    }

  def parseFrame(tpe: FrameType, flags: ByteFlag, streamId: Int, payload: ByteReader): FrameEvent = {

    // TODO: add @switch? seems non-trivial for now
    tpe match {
      case HEADERS ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)
        val endHeaders = Flags.END_HEADERS.isSet(flags)
        val priority = Flags.PRIORITY.isSet(flags)

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
        HeadersFrame(flags, streamId, payload.take(payload.remainingSize - paddingLength))

      case DATA ⇒
        val pad = Flags.PADDED.isSet(flags)
        val endStream = Flags.END_STREAM.isSet(flags)

        val paddingLength =
          if (pad) payload.readByte() & 0xff
          else 0

        DataFrame(flags, streamId, payload.take(payload.remainingSize - paddingLength))

      case SETTINGS ⇒
        val ack = Flags.ACK.isSet(flags)

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

          SettingsFrame(flags, readSettings(Nil))
        }

      case WINDOW_UPDATE ⇒
        // TODO: check frame size
        // TODO: check flags
        // TODO: check reserved flag
        // TODO: check that increment is > 0
        val increment = payload.readIntBE()
        WindowUpdateFrame(flags, streamId, increment)

      case CONTINUATION ⇒
        val endHeaders = Flags.END_HEADERS.isSet(flags)
        // TODO: check that streamId > 0

        ContinuationFrame(flags, streamId, endHeaders, payload.remainingData)

      case tpe ⇒ // TODO: remove once all stream types are defined
        UnknownFrameEvent(flags, tpe, streamId, payload.remainingData)
    }
  }
}
