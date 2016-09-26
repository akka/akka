package akka.http.impl.engine.http2

import akka.NotUsed
import akka.http.impl.engine.http2.Http2Protocol.Flags
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http2
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpProtocols
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.model.headers.CacheDirectives
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.ActorMaterializer
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.Future

class Http2ServerSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  "The Http/2 server implementation" should {
    "respect settings" should {
      "initial MAX_FRAME_SIZE" in pending
      "received SETTINGS_MAX_FRAME_SIZE" in pending

      "not exceed connection-level window while sending" in pending
      "not exceed stream-level window while sending" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed when window became negative through setting" in pending

      "received SETTINGS_MAX_CONCURRENT_STREAMS" in pending
    }

    "support low-level features" should {
      "eventually send WINDOW_UPDATE frames for received data" in pending
      "respond to PING frames" in pending
      "acknowledge SETTINGS frames" in pending
    }

    "respect the substream state machine" should {
      "reject other frame than HEADERS/PUSH_PROMISE in idle state with connection-level PROTOCOL_ERROR (5.1)" in pending
      "reject incoming frames on already half-closed substream" in pending

      "reject even-numbered client-initiated substreams" in pending

      "reject all other frames while waiting for CONTINUATION frames" in pending

      "reject double sub-streams creation" in pending
      "reject substream creation for streams invalidated by skipped substream IDs" in pending
    }

    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with WithProbes {
        def requestResponseRoundtrip(
          streamId:                    Int,
          requestHeaderBlock:          ByteString,
          expectedRequest:             HttpRequest,
          response:                    HttpResponse,
          expectedResponseHeaderBlock: ByteString
        ): Unit = {
          sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
          expectRequest().removeHeader("x-http2-stream-id") shouldBe expectedRequest

          responseOut.sendNext(response.addHeader(Http2StreamIdHeader(streamId)))
          val headerPayload = expectHeaderBlock(streamId)
          headerPayload shouldBe expectedResponseHeaderBlock
        }
      }

      "GET request in one HEADERS frame" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )
      }
      "Three consecutive GET requests" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )
        requestResponseRoundtrip(
          streamId = 3,
          requestHeaderBlock = HPackSpecExamples.C42SecondRequestWithHuffman,
          expectedRequest = HttpRequest(
            method = HttpMethods.GET,
            uri = "http://www.example.com/",
            // FIXME: should be modeled header: headers.`Cache-Control`(CacheDirectives.`no-cache`()) :: Nil,
            headers = RawHeader("cache-control", "no-cache") :: Nil,
            protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.SecondResponse,
          // our hpack compressor chooses the non-huffman form probably because it seems to have same length
          expectedResponseHeaderBlock = HPackSpecExamples.C52SecondResponseWithoutHuffman
        )
        requestResponseRoundtrip(
          streamId = 5,
          requestHeaderBlock = HPackSpecExamples.C43ThirdRequestWithHuffman,
          expectedRequest = HttpRequest(
            method = HttpMethods.GET,
            uri = "https://www.example.com/index.html",
            headers = RawHeader("custom-key", "custom-value") :: Nil,
            protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.ThirdResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C63ThirdResponseWithHuffman
        )
      }
      "GET request in one HEADERS and one CONTINUATION frame" in new TestSetup with WithProbes {
        pending // needs CONTINUATION frame parsing and actual support in decompression

        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman
        val fragment1 = headerBlock.take(5)
        val fragment2 = headerBlock.drop(5)

        sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        requestIn.ensureSubscription()
        requestIn.expectNoMsg()
        sendCONTINUATION(1, endHeaders = true, fragment2)

        val request = expectRequest()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamIdHeader = request.header[Http2StreamIdHeader].get
        responseOut.sendNext(HPackSpecExamples.FirstResponse.addHeader(streamIdHeader))
        val headerPayload = expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }

      "fail if Http2StreamIdHeader missing" in pending
      "automatically add `Date` header" in pending
      "parse headers to modeled headers" in pending
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" in pending
      "send two responses concurrently" in pending
    }
  }

  abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    val netIn = ByteStringSinkProbe()
    val netOut = TestPublisher.probe[ByteString]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed]

    handlerFlow
      .join(Http2Blueprint.serverStack())
      .runWith(Source.fromPublisher(netOut), netIn.sink)

    def sendBytes(bytes: ByteString): Unit = netOut.sendNext(bytes)
    def expectBytes(bytes: ByteString): Unit = netIn.expectBytes(bytes)
    def expectBytes(num: Int): ByteString = netIn.expectBytes(num)

    def expectFrame(frameType: FrameType, flags: ByteFlag, streamId: Int): ByteString = {
      val header = expectFrameHeader()
      header.frameType shouldBe frameType
      header.flags shouldBe flags
      header.streamId shouldBe streamId
      expectBytes(header.payloadLength)
    }

    def expectFrameHeader(): FrameHeader = {
      val headerBytes = expectBytes(9)
      val reader = new ByteReader(headerBytes)
      val length = reader.readShortBE() << 8 | reader.readByte()
      val tpe = Http2Protocol.FrameType.byId(reader.readByte())
      val flags = new ByteFlag(reader.readByte())
      val streamId = reader.readIntBE()

      FrameHeader(tpe, flags, streamId, length)
    }

    /** Collect a header block maybe spanning several frames */
    def expectHeaderBlock(streamId: Int): ByteString =
      // FIXME: also collect CONTINUATION frames as long as END_HEADERS is not set
      expectFrame(FrameType.HEADERS, Flags.END_STREAM | Flags.END_HEADERS, streamId)

    def sendFrame(frameType: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): Unit =
      sendBytes(FrameRenderer.renderFrame(frameType, flags, streamId, payload))

    def sendHEADERS(streamId: Int, endStream: Boolean, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment)))
    def sendCONTINUATION(streamId: Int, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(ContinuationFrame(streamId, endHeaders, headerBlockFragment)))
  }
  case class FrameHeader(frameType: FrameType, flags: ByteFlag, streamId: Int, payloadLength: Int)
  abstract class TestSetup extends TestSetupWithoutHandshake {
    sendBytes(Http2Protocol.ClientConnectionPreface)
    val serverPreface = expectFrameHeader()
    serverPreface.frameType shouldBe Http2Protocol.FrameType.SETTINGS
  }

  trait WithProbes extends TestSetupWithoutHandshake {
    lazy val requestIn = TestSubscriber.probe[HttpRequest]()
    lazy val responseOut = TestPublisher.probe[HttpResponse]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow.fromSinkAndSource(Sink.fromSubscriber(requestIn), Source.fromPublisher(responseOut))

    def expectRequest(): HttpRequest = requestIn.requestNext()
  }
  trait WithHandler extends TestSetupWithoutHandshake {
    def parallelism: Int = 2
    def handler: HttpRequest ⇒ Future[HttpResponse] =
      _ ⇒ Future.successful(HttpResponse())

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)
  }
}
