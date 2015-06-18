/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import java.net.InetSocketAddress
import akka.http.ClientConnectionSettings
import akka.stream.io.{ SessionBytes, SslTlsOutbound, SendBytes }
import org.scalatest.Inside
import akka.util.ByteString
import akka.event.NoLogging
import akka.stream.ActorFlowMaterializer
import akka.stream.testkit._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._

class LowLevelOutgoingConnectionSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") with Inside {
  implicit val materializer = ActorFlowMaterializer()

  "The connection-level client implementation" should {

    "handle a request/response round-trip" which {

      "has a request with empty entity" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a request with default entity" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XY"))
        expectWireData("XY")
        sub.sendComplete()

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a response with a chunked entity" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFX\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("DEFX"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        probe.expectComplete()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "exhibits eager request stream completion" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        requestsSub.sendComplete()
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)
        responses.expectNext(HttpResponse())

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "handle several requests on one persistent connection" which {
      "has a first response that was chunked" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        probe.expectComplete()

        // simulate that response is received before method bypass reaches response parser
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        responsesSub.request(1)

        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")
        requestsSub.sendComplete()
        responses.expectNext(HttpResponse())

        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }
    }

    "produce proper errors" which {

      "catch the entity stream being shorter than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendComplete()

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to 2 bytes less"
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "catch the entity stream being longer than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest(4)
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XYZ"))

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to more bytes"
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "catch illegal response starts" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.2 200 OK
            |
            |""")

        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "The server-side HTTP version is not supported"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }

      "catch illegal response chunks" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("4\nDEFXX")
        sub.request(1)
        val error @ EntityStreamException(info) = probe.expectError()
        info.summary shouldEqual "Illegal chunk termination"

        responses.expectComplete()
        netOut.expectComplete()
        requestsSub.expectCancellation()
      }

      "catch a response start truncation" in new TestSetup {
        requestsSub.sendNext(HttpRequest())
        expectWireData(
          """GET / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |
            |""")

        netInSub.expectRequest()
        sendWireData("HTTP/1.1 200 OK")
        netInSub.sendComplete()

        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "Illegal HTTP message start"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }
    }
  }

  class TestSetup {
    val requests = TestPublisher.manualProbe[HttpRequest]
    val responses = TestSubscriber.manualProbe[HttpResponse]
    val remoteAddress = new InetSocketAddress("example.com", 0)

    def settings = ClientConnectionSettings(system)
      .copy(userAgentHeader = Some(`User-Agent`(List(ProductVersion("akka-http", "test")))))

    val (netOut, netIn) = {
      val netOut = TestSubscriber.manualProbe[ByteString]
      val netIn = TestPublisher.manualProbe[ByteString]

      FlowGraph.closed(OutgoingConnectionBlueprint(Host(remoteAddress), settings, NoLogging)) { implicit b ⇒
        client ⇒
          import FlowGraph.Implicits._
          Source(netIn) ~> Flow[ByteString].map(SessionBytes(null, _)) ~> client.in2
          client.out1 ~> Flow[SslTlsOutbound].collect { case SendBytes(x) ⇒ x } ~> Sink(netOut)
          Source(requests) ~> client.in1
          client.out2 ~> Sink(responses)
      }.run()

      netOut -> netIn
    }

    def wipeDate(string: String) =
      string.fastSplit('\n').map {
        case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
        case s                          ⇒ s
      }.mkString("\n")

    val netInSub = netIn.expectSubscription()
    val netOutSub = netOut.expectSubscription()
    val requestsSub = requests.expectSubscription()
    val responsesSub = responses.expectSubscription()

    def sendWireData(data: String): Unit = sendWireData(ByteString(data.stripMarginWithNewline("\r\n"), "ASCII"))
    def sendWireData(data: ByteString): Unit = netInSub.sendNext(data)

    def expectWireData(s: String) = {
      netOutSub.request(1)
      netOut.expectNext().utf8String shouldEqual s.stripMarginWithNewline("\r\n")
    }

    def closeNetworkInput(): Unit = netInSub.sendComplete()
  }
}
