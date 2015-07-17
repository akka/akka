/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.http.impl.engine.ws.Protocol.Opcode
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl.{ Keep, Sink, Flow, Source }
import akka.stream.testkit.Utils
import akka.util.ByteString
import org.scalatest.{ Matchers, FreeSpec }

import akka.http.impl.util._

import akka.http.impl.engine.server.HttpServerTestSetupBase

import scala.util.Random

class WebsocketServerSpec extends FreeSpec with Matchers with WithMaterializerSpec { spec ⇒
  import WSTestUtils._

  "The server-side Websocket integration should" - {
    "establish a websocket connection when the user requests it" - {
      "when user handler instantly tries to send messages" in Utils.assertAllStagesStopped {
        new TestSetup {
          send(
            """GET /chat HTTP/1.1
              |Host: server.example.com
              |Upgrade: websocket
              |Connection: Upgrade
              |Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
              |Origin: http://example.com
              |Sec-WebSocket-Version: 13
              |
              |""".stripMarginWithNewline("\r\n"))

          val request = expectRequest
          val upgrade = request.header[UpgradeToWebsocket]
          upgrade.isDefined shouldBe true

          val source =
            Source(List(1, 2, 3, 4, 5)).map(num ⇒ TextMessage.Strict(s"Message $num"))
          val handler = Flow.wrap(Sink.ignore, source)(Keep.none)
          val response = upgrade.get.handleMessages(handler)
          responsesSub.sendNext(response)

          wipeDate(expectNextChunk().utf8String) shouldEqual
            """HTTP/1.1 101 Switching Protocols
              |Upgrade: websocket
              |Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
              |Server: akka-http/test
              |Date: XXXX
              |Connection: upgrade
              |
              |""".stripMarginWithNewline("\r\n")

          expectWSFrame(Protocol.Opcode.Text,

            ByteString("Message 1"), fin = true)
          expectWSFrame(Protocol.
            Opcode.Text, ByteString("Message 2"), fin = true)
          expectWSFrame(
            Protocol.Opcode.Text, ByteString("Message 3"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true)
          expectWSCloseFrame(Protocol.CloseCodes.Regular)

          sendWSCloseFrame(Protocol.CloseCodes.Regular, mask = true)
          closeNetworkInput()
          expectNetworkClose()
        }
      }
      "for echoing user handler" in Utils.assertAllStagesStopped {
        new TestSetup {

          send(
            """GET /echo HTTP/1.1
              |Host: server.example.com
              |Upgrade: websocket
              |Connection: Upgrade
              |Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
              |Origin: http://example.com
              |Sec-WebSocket-Version: 13
              |
              |""".stripMarginWithNewline("\r\n"))

          val request = expectRequest
          val upgrade = request.header[UpgradeToWebsocket]
          upgrade.isDefined shouldBe true

          val response = upgrade.get.handleMessages(Flow[Message]) // simple echoing
          responsesSub.sendNext(response)

          wipeDate(expectNextChunk().utf8String) shouldEqual
            """HTTP/1.1 101 Switching Protocols
              |Upgrade: websocket
              |Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
              |Server: akka-http/test
              |Date: XXXX
              |Connection: upgrade
              |
              |""".stripMarginWithNewline("\r\n")

          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 1"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 1"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 2"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 2"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 3"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 3"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 4"), fin = true)
          sendWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true, mask = true)
          expectWSFrame(Protocol.Opcode.Text, ByteString("Message 5"), fin = true)

          sendWSCloseFrame(Protocol.CloseCodes.Regular, mask = true)
          expectWSCloseFrame(Protocol.CloseCodes.Regular)

          closeNetworkInput()
          expectNetworkClose()
        }
      }
    }
    "prevent the selection of an unavailable subprotocol" in pending
    "reject invalid Websocket handshakes" - {
      "missing `Connection: upgrade` header" in pending
      "missing `Sec-WebSocket-Key header" in pending
      "`Sec-WebSocket-Key` with wrong amount of base64 encoded data" in pending
      "missing `Sec-WebSocket-Version` header" in pending
      "unsupported `Sec-WebSocket-Version`" in pending
    }
  }

  class TestSetup extends HttpServerTestSetupBase {
    implicit def system = spec.system
    implicit def materializer = spec.materializer

    def sendWSFrame(opcode: Opcode,
                    data: ByteString,
                    fin: Boolean,
                    mask: Boolean = false,
                    rsv1: Boolean = false,
                    rsv2: Boolean = false,
                    rsv3: Boolean = false): Unit = {
      val (theMask, theData) =
        if (mask) {
          val m = Random.nextInt()
          (Some(m), maskedBytes(data, m)._1)
        } else (None, data)
      send(frameHeader(opcode, data.length, fin, theMask, rsv1, rsv2, rsv3) ++ theData)
    }

    def sendWSCloseFrame(closeCode: Int, mask: Boolean = false): Unit =
      send(closeFrame(closeCode, mask))

    def expectNextChunk(): ByteString = {
      netOutSub.request(1)
      netOut.expectNext()
    }

    def expectWSFrame(opcode: Opcode,
                      data: ByteString,
                      fin: Boolean,
                      mask: Option[Int] = None,
                      rsv1: Boolean = false,
                      rsv2: Boolean = false,
                      rsv3: Boolean = false): Unit =
      expectNextChunk() shouldEqual frameHeader(opcode, data.length, fin, mask, rsv1, rsv2, rsv3) ++ data
    def expectWSCloseFrame(closeCode: Int, mask: Boolean = false): Unit =
      expectNextChunk() shouldEqual closeFrame(closeCode, mask)
  }
}
