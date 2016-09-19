/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine.http2

import akka.http.impl.engine.ws.{ BitBuilder, WithMaterializerSpec }
import akka.http.impl.util._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.{ FreeSpec, Matchers }
import org.scalatest.matchers.Matcher

import scala.collection.immutable
import scala.concurrent.duration._

class Http2FramingSpec extends FreeSpec with Matchers with WithMaterializerSpec {
  import BitBuilder._

  import Http2Protocol.Flags._

  "The WebSocket parser/renderer round-trip should work for" - {
    "DATA frames" - {
      "without padding" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=5   # length
            00000000     # type = 0x0 = DATA
            00000001     # flags = END_STREAM
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=234223ab # stream ID
            xxxxxxxx=61  # data
            xxxxxxxx=62
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
         """ should parseTo(DataFrame(END_STREAM, 0x234223ab, ByteString("abcde")))
      }
      "with padding" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=c   # length = 11 = 1 byte padding size + 5 bytes padding + 6 bytes data
            00000000     # type = 0x0 = DATA
            00001000     # flags = PADDED
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=234223ab # stream ID
            xxxxxxxx=5
            xxxxxxxx=62  # data
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
            xxxxxxxx=67
            00000000     # padding
            00000000
            00000000
            00000000
            00000000
         """ should parseTo(DataFrame(PADDED, 0x234223ab, ByteString("bcdefg")), checkRendering = false)
      }
    }
    "HEADER frames" - {
      "without padding + priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=5   # length
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00000100     # flags = END_HEADERS
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=3546 # stream ID
            xxxxxxxx=61  # data
            xxxxxxxx=62
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
         """ should parseTo(HeadersFrame(END_HEADERS, 0x3546, ByteString("abcde")))
      }
      "with padding but without priority settings" in {
        b"""xxxxxxxx
            xxxxxxxx
            xxxxxxxx=a   # length = 10 = 1 byte padding size + 3 bytes padding + 6 bytes payload
            xxxxxxxx=1   # type = 0x1 = HEADERS
            00001000     # flags = PADDED
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=3546 # stream ID
            xxxxxxxx=3
            xxxxxxxx=62  # data
            xxxxxxxx=63
            xxxxxxxx=64
            xxxxxxxx=65
            xxxxxxxx=66
            xxxxxxxx=67
            00000000     # padding
            00000000
            00000000
         """ should parseTo(HeadersFrame(PADDED, 0x3546, ByteString("bcdefg")), checkRendering = false)
      }
      "with padding and priority settings" in pending
    }
  }

  private def parseTo(events: FrameEvent*): Matcher[ByteString] =
    parseMultipleTo(events: _*).compose(Seq(_)) // TODO: try random chunkings

  private def parseTo(event: FrameEvent, checkRendering: Boolean): Matcher[ByteString] =
    parseMultipleTo(Seq(event), checkRendering).compose(Seq(_)) // TODO: try random chunkings

  private def parseMultipleTo(events: FrameEvent*): Matcher[Seq[ByteString]] =
    parseMultipleTo(events, true)

  private def parseMultipleTo(events: Seq[FrameEvent], checkRendering: Boolean): Matcher[Seq[ByteString]] =
    equal(events).matcher[Seq[FrameEvent]].compose {
      (chunks: Seq[ByteString]) ⇒
        val result = parseToEvents(chunks)
        result shouldEqual events

        if (checkRendering) {
          val rendered = renderToByteString(result)
          rendered shouldEqual chunks.reduce(_ ++ _)
        }
        result
    }

  private def parseToEvents(bytes: Seq[ByteString]): immutable.Seq[FrameEvent] =
    Source(bytes.toVector).via(new FrameParser(shouldReadPreface = false)).runFold(Vector.empty[FrameEvent])(_ :+ _)
      .awaitResult(1.second)
  private def renderToByteString(events: immutable.Seq[FrameEvent]): ByteString =
    Source(events).map(FrameRenderer.render).runFold(ByteString.empty)(_ ++ _)
      .awaitResult(1.second)
}
