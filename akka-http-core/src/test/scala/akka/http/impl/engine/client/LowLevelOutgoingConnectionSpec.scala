/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.client

import scala.concurrent.duration._
import scala.reflect.ClassTag
import org.scalatest.Inside
import akka.http.ClientConnectionSettings
import akka.stream.io.{ SessionBytes, SslTlsOutbound, SendBytes }
import akka.util.ByteString
import akka.event.NoLogging
import akka.stream.{ ClosedShape, ActorMaterializer }
import akka.stream.testkit._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.HttpEntity._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._

class LowLevelOutgoingConnectionSpec extends AkkaSpec("akka.loggers = []\n akka.loglevel = OFF") with Inside {
  implicit val materializer = ActorMaterializer()

  "The connection-level client implementation" should {

    "handle a request/response round-trip" which {

      "has a request with empty entity" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a request with default entity" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XY"))
        expectWireData("XY")
        sub.sendComplete()

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

        requestsSub.sendComplete()
        netOut.expectComplete()
        netInSub.sendComplete()
        responses.expectComplete()
      }

      "has a response with a chunked entity" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = expectResponse()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
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
        sub.request(1)
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

        sendWireData(
          """HTTP/1.1 200 OK
            |Content-Length: 0
            |
            |""")

        expectResponse() shouldEqual HttpResponse()

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

        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = expectResponse()

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
        val sub = probe.expectSubscription()

        sendWireData("3\nABC\n")
        sub.request(1)
        probe.expectNext(HttpEntity.Chunk("ABC"))

        sendWireData("0\n\n")
        sub.request(1)
        probe.expectNext(HttpEntity.LastChunk)
        sub.request(1)
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

      "catch the request entity stream being shorter than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendComplete()

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to 2 bytes less"
        netInSub.sendComplete()
        responsesSub.request(1)
        responses.expectError(One2OneBidiFlow.OutputTruncationException)
      }

      "catch the request entity stream being longer than the Content-Length" in new TestSetup {
        val probe = TestPublisher.manualProbe[ByteString]()
        requestsSub.sendNext(HttpRequest(PUT, entity = HttpEntity(ContentTypes.`application/octet-stream`, 8, Source.fromPublisher(probe))))
        expectWireData(
          """PUT / HTTP/1.1
            |Host: example.com
            |User-Agent: akka-http/test
            |Content-Type: application/octet-stream
            |Content-Length: 8
            |
            |""")
        val sub = probe.expectSubscription()
        sub.expectRequest()
        sub.sendNext(ByteString("ABC"))
        expectWireData("ABC")
        sub.sendNext(ByteString("DEF"))
        expectWireData("DEF")
        sub.sendNext(ByteString("XYZ"))

        val InvalidContentLengthException(info) = netOut.expectError()
        info.summary shouldEqual "HTTP message had declared Content-Length 8 but entity data stream amounts to more bytes"
        netInSub.sendComplete()
        responsesSub.request(1)
        responses.expectError(One2OneBidiFlow.OutputTruncationException)
      }

      "catch illegal response starts" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.2 200 OK
            |
            |""")

        responsesSub.request(1)
        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "The server-side HTTP version is not supported"
        netOut.expectError(error)
        requestsSub.expectCancellation()
        netInSub.expectCancellation()
      }

      "catch illegal response chunks" in new TestSetup {
        sendStandardRequest()
        sendWireData(
          """HTTP/1.1 200 OK
            |Transfer-Encoding: chunked
            |
            |""")

        responsesSub.request(1)
        val HttpResponse(_, _, HttpEntity.Chunked(ct, chunks), _) = responses.expectNext()
        ct shouldEqual ContentTypes.`application/octet-stream`

        val probe = TestSubscriber.manualProbe[ChunkStreamPart]()
        chunks.runWith(Sink.fromSubscriber(probe))
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
        netInSub.expectCancellation()
      }

      "catch a response start truncation" in new TestSetup {
        sendStandardRequest()
        sendWireData("HTTP/1.1 200 OK")
        netInSub.sendComplete()

        responsesSub.request(1)
        val error @ IllegalResponseException(info) = responses.expectError()
        info.summary shouldEqual "Illegal HTTP message start"
        netOut.expectError(error)
        requestsSub.expectCancellation()
      }
    }

    def isDefinedVia = afterWord("is defined via")
    "support response length verification" which isDefinedVia {
      import HttpEntity._

      class LengthVerificationTest(maxContentLength: Int) extends TestSetup(maxContentLength) {
        val entityBase = "0123456789ABCD"

        def sendStrictResponseWithLength(bytes: Int) =
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Content-Length: $bytes
               |
               |${entityBase take bytes}""")
        def sendDefaultResponseWithLength(bytes: Int) = {
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Content-Length: $bytes
               |
               |${entityBase take 3}""")
          sendWireData(entityBase.slice(3, 7))
          sendWireData(entityBase.slice(7, bytes))
        }
        def sendChunkedResponseWithLength(bytes: Int) =
          sendWireData(
            s"""HTTP/1.1 200 OK
               |Transfer-Encoding: chunked
               |
               |3
               |${entityBase take 3}
               |4
               |${entityBase.slice(3, 7)}
               |${bytes - 7}
               |${entityBase.slice(7, bytes)}
               |0
               |
               |""")
        def sendCloseDelimitedResponseWithLength(bytes: Int) = {
          sendWireData(
            s"""HTTP/1.1 200 OK
               |
               |${entityBase take 3}""")
          sendWireData(entityBase.slice(3, 7))
          sendWireData(entityBase.slice(7, bytes))
          netInSub.sendComplete()
        }

        implicit class XResponse(response: HttpResponse) {
          def expectStrictEntityWithLength(bytes: Int) =
            response shouldEqual HttpResponse(
              entity = Strict(ContentTypes.`application/octet-stream`, ByteString(entityBase take bytes)))

          def expectEntity[T <: HttpEntity: ClassTag](bytes: Int) =
            inside(response) {
              case HttpResponse(_, _, entity: T, _) ⇒
                entity.toStrict(100.millis).awaitResult(100.millis).data.utf8String shouldEqual entityBase.take(bytes)
            }

          def expectSizeErrorInEntityOfType[T <: HttpEntity: ClassTag](limit: Int, actualSize: Option[Long] = None) =
            inside(response) {
              case HttpResponse(_, _, entity: T, _) ⇒
                def gatherBytes = entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(100.millis)
                (the[Exception] thrownBy gatherBytes).getCause shouldEqual EntityStreamSizeException(limit, actualSize)
            }
        }
      }

      "the config setting (strict entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().expectStrictEntityWithLength(10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "the config setting (default entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "the config setting (chunked entity)" in new LengthVerificationTest(maxContentLength = 10) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "the config setting (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 10) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 10) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }

      "a smaller programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectStrictEntityWithLength(10)

        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a smaller programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a smaller programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 12) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "a smaller programmatically-set limit (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 12) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 12) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }

      "a larger programmatically-set limit (strict entity)" in new LengthVerificationTest(maxContentLength = 8) {
        // entities that would be strict but have a Content-Length > the configured maximum are delivered
        // as single element Default entities!
        sendStandardRequest()
        sendStrictResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendStrictResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a larger programmatically-set limit (default entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendStandardRequest()
        sendDefaultResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Default](10)

        sendStandardRequest()
        sendDefaultResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Default](limit = 10, actualSize = Some(11))
      }

      "a larger programmatically-set limit (chunked entity)" in new LengthVerificationTest(maxContentLength = 8) {
        sendStandardRequest()
        sendChunkedResponseWithLength(10)
        expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[Chunked](10)

        sendStandardRequest()
        sendChunkedResponseWithLength(11)
        expectResponse().mapEntity(_ withSizeLimit 10)
          .expectSizeErrorInEntityOfType[Chunked](limit = 10)
      }

      "a larger programmatically-set limit (close-delimited entity)" in {
        new LengthVerificationTest(maxContentLength = 8) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(10)
          expectResponse().mapEntity(_ withSizeLimit 10).expectEntity[CloseDelimited](10)
        }
        new LengthVerificationTest(maxContentLength = 8) {
          sendStandardRequest()
          sendCloseDelimitedResponseWithLength(11)
          expectResponse().mapEntity(_ withSizeLimit 10).expectSizeErrorInEntityOfType[CloseDelimited](limit = 10)
        }
      }
    }
  }

  class TestSetup(maxResponseContentLength: Int = -1) {
    val requests = TestPublisher.manualProbe[HttpRequest]()
    val responses = TestSubscriber.manualProbe[HttpResponse]()

    def settings = {
      val s = ClientConnectionSettings(system)
        .copy(userAgentHeader = Some(`User-Agent`(List(ProductVersion("akka-http", "test")))))
      if (maxResponseContentLength < 0) s
      else s.copy(parserSettings = s.parserSettings.copy(maxContentLength = maxResponseContentLength))
    }

    val (netOut, netIn) = {
      val netOut = TestSubscriber.manualProbe[ByteString]
      val netIn = TestPublisher.manualProbe[ByteString]()

      RunnableGraph.fromGraph(GraphDSL.create(OutgoingConnectionBlueprint(Host("example.com"), settings, NoLogging)) { implicit b ⇒
        client ⇒
          import GraphDSL.Implicits._
          Source.fromPublisher(netIn) ~> Flow[ByteString].map(SessionBytes(null, _)) ~> client.in2
          client.out1 ~> Flow[SslTlsOutbound].collect { case SendBytes(x) ⇒ x } ~> Sink.fromSubscriber(netOut)
          Source.fromPublisher(requests) ~> client.in1
          client.out2 ~> Sink.fromSubscriber(responses)
          ClosedShape
      }).run()

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

    requestsSub.expectRequest(16)
    netInSub.expectRequest(16)

    def sendWireData(data: String): Unit = sendWireData(ByteString(data.stripMarginWithNewline("\r\n"), "ASCII"))
    def sendWireData(data: ByteString): Unit = netInSub.sendNext(data)

    def expectWireData(s: String) = {
      netOutSub.request(1)
      netOut.expectNext().utf8String shouldEqual s.stripMarginWithNewline("\r\n")
    }

    def closeNetworkInput(): Unit = netInSub.sendComplete()

    def sendStandardRequest() = {
      requestsSub.sendNext(HttpRequest())
      expectWireData(
        """GET / HTTP/1.1
          |Host: example.com
          |User-Agent: akka-http/test
          |
          |""")
    }

    def expectResponse() = {
      responsesSub.request(1)
      responses.expectNext()
    }
  }
}
