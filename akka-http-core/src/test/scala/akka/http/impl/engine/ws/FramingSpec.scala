/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import scala.collection.immutable
import scala.concurrent.duration._
import org.scalatest.matchers.Matcher
import org.scalatest.{ FreeSpec, Matchers }
import akka.util.ByteString
import akka.stream.scaladsl.Source
import akka.stream.stage.Stage
import akka.http.impl.util._

import Protocol.Opcode

class FramingSpec extends FreeSpec with Matchers with WithMaterializerSpec {
  import BitBuilder._

  "The WebSocket parser/renderer round-trip should work for" - {
    "the frame header" - {
      "interpret flags correctly" - {
        "FIN" in {
          b"""1000     # flags
                  0000 # opcode
              0        # mask?
               0000000 # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0, fin = true))
        }
        "RSV1" in {
          b"""0100     # flags
                  0000 # opcode
              0        # mask?
               0000000 # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0, fin = false, rsv1 = true))
        }
        "RSV2" in {
          b"""0010     # flags
                  0000 # opcode
              0        # mask?
               0000000 # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0, fin = false, rsv2 = true))
        }
        "RSV3" in {
          b"""0001     # flags
                  0000 # opcode
              0        # mask?
               0000000 # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0, fin = false, rsv3 = true))
        }
      }
      "interpret opcode correctly" - {
        "Continuation" in {
          b"""0000       # flags
                  xxxx=0 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0, fin = false))
        }
        "Text" in {
          b"""0000       # flags
                  xxxx=1 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Text, None, 0, fin = false))
        }
        "Binary" in {
          b"""0000       # flags
                  xxxx=2 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Binary, None, 0, fin = false))
        }

        "Close" in {
          b"""0000       # flags
                  xxxx=8 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Close, None, 0, fin = false))
        }
        "Ping" in {
          b"""0000       # flags
                  xxxx=9 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Ping, None, 0, fin = false))
        }
        "Pong" in {
          b"""0000       # flags
                  xxxx=a # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Pong, None, 0, fin = false))
        }

        "Other" in {
          b"""0000       # flags
                  xxxx=6 # opcode
              0          # mask?
               0000000   # length
          """ should parseTo(FrameHeader(Opcode.Other(6), None, 0, fin = false))
        }
      }
      "read mask correctly" in {
        b"""0000     # flags
                0000 # opcode
            1        # mask?
             0000000 # length
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx
            xxxxxxxx=a1b2c3d4
          """ should parseTo(FrameHeader(Opcode.Continuation, Some(0xa1b2c3d4), 0, fin = false))
      }
      "read length" - {
        "< 126" in {
          b"""0000       # flags
                  xxxx=0 # opcode
              0          # mask?
               xxxxxxx=5 # length
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 5, fin = false))
        }
        "126" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7e   # length
              xxxxxxxx
              xxxxxxxx=007e # length16
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 126, fin = false))
        }
        "127" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7e   # length
              xxxxxxxx
              xxxxxxxx=007f # length16
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 127, fin = false))
        }
        "127 < length < 65536" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7e   # length
              xxxxxxxx
              xxxxxxxx=d28e # length16
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0xd28e, fin = false))
        }
        "65535" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7e   # length
              xxxxxxxx
              xxxxxxxx=ffff # length16
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0xffff, fin = false))
        }
        "65536" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=0000000000010000 # length64
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0x10000, fin = false))
        }
        "> 65536" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=0000000123456789 # length64
          """ should parseTo(FrameHeader(Opcode.Continuation, None, 0x123456789L, fin = false))
        }
        "Long.MaxValue" in {
          b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=7fffffffffffffff # length64
          """ should parseTo(FrameHeader(Opcode.Continuation, None, Long.MaxValue, fin = false))
        }
      }
    }

    "a partial frame" in {
      val header =
        b"""0000       # flags
                xxxx=1 # opcode
            0          # mask?
             xxxxxxx=5 # length
          """
      val data = ByteString("abc")

      (header ++ data) should parseTo(
        FrameStart(
          FrameHeader(Opcode.Text, None, 5, fin = false),
          data))
    }
    "a partial frame of total size > Int.MaxValue" in {
      val header =
        b"""0000          # flags
                  xxxx=0    # opcode
              0             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=00000000ffffffff # length64
          """
      val data = ByteString("abc", "ASCII")

      Seq(header, data) should parseMultipleTo(
        FrameStart(FrameHeader(Opcode.Continuation, None, 0xFFFFFFFFL, fin = false), ByteString.empty),
        FrameData(data, lastPart = false))
    }
    "a full frame" in {
      val header =
        b"""0000       # flags
                xxxx=0 # opcode
            0          # mask?
             xxxxxxx=5 # length
        """
      val data = ByteString("abcde")

      (header ++ data) should parseTo(
        FrameStart(FrameHeader(Opcode.Continuation, None, 5, fin = false), data))
    }
    "a full frame in chunks" in {
      val header =
        b"""0000       # flags
                xxxx=1 # opcode
            0          # mask?
             xxxxxxx=5 # length
          """
      val data1 = ByteString("abc")
      val data2 = ByteString("de")

      val expectedHeader = FrameHeader(Opcode.Text, None, 5, fin = false)
      Seq(header, data1, data2) should parseMultipleTo(
        FrameStart(expectedHeader, ByteString.empty),
        FrameData(data1, lastPart = false),
        FrameData(data2, lastPart = true))
    }
    "several frames" in {
      val header1 =
        b"""0000       # flags
                xxxx=0 # opcode
            0          # mask?
             xxxxxxx=5 # length
        """

      val header2 =
        b"""0000       # flags
                xxxx=0 # opcode
            0          # mask?
             xxxxxxx=7 # length
        """

      val data1 = ByteString("abcde")
      val data2 = ByteString("abc")

      (header1 ++ data1 ++ header2 ++ data2) should parseTo(
        FrameStart(FrameHeader(Opcode.Continuation, None, 5, fin = false), data1),
        FrameStart(FrameHeader(Opcode.Continuation, None, 7, fin = false), data2))
    }
  }

  private def parseTo(events: FrameEvent*): Matcher[ByteString] =
    parseMultipleTo(events: _*).compose(Seq(_))

  private def parseMultipleTo(events: FrameEvent*): Matcher[Seq[ByteString]] =
    equal(events).matcher[Seq[FrameEvent]].compose {
      (chunks: Seq[ByteString]) ⇒
        val result = parseToEvents(chunks)
        result shouldEqual events
        val rendered = renderToByteString(result)
        rendered shouldEqual chunks.reduce(_ ++ _)
        result
    }

  private def parseToEvents(bytes: Seq[ByteString]): immutable.Seq[FrameEvent] =
    Source(bytes.toVector).via(FrameEventParser).runFold(Vector.empty[FrameEvent])(_ :+ _)
      .awaitResult(1.second)
  private def renderToByteString(events: immutable.Seq[FrameEvent]): ByteString =
    Source(events).transform(newRenderer).runFold(ByteString.empty)(_ ++ _)
      .awaitResult(1.second)

  protected def newRenderer(): Stage[FrameEvent, ByteString] = new FrameEventRenderer

  import scala.language.implicitConversions
  private implicit def headerToEvent(header: FrameHeader): FrameEvent =
    FrameStart(header, ByteString.empty)
}
