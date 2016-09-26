package akka.http.impl.engine.http2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

import akka.NotUsed
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.Flags
import akka.http.impl.engine.http2.Http2Protocol.FrameType
import akka.http.impl.engine.http2.parsing.HttpRequestHeaderHpackDecompression
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpProtocols
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.stream.ActorMaterializer
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.testkit.AkkaSpec
import akka.util.ByteString
import akka.util.ByteStringBuilder
import com.twitter.hpack.Decoder
import com.twitter.hpack.Encoder
import com.twitter.hpack.HeaderListener

import scala.annotation.tailrec
import scala.concurrent.Future

class Http2ServerSpec extends AkkaSpec with WithInPendingUntilFixed {
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
          expectRequest() shouldBe expectedRequest

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
      "GET request in one HEADERS and one CONTINUATION frame" inPendingUntilFixed new TestSetup with WithProbes {
        // FIXME: needs CONTINUATION frame parsing and actual support in decompression

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

    "support stream support for request entity data" should {
      "send data frames to entity stream" in pending
      "fail entity stream if peer sends RST_STREAM frame" in pending
      "send RST_STREAM if entity stream is canceled" in pending
    }

    "support stream support for sending response entity data" should {
      class WaitingForResponseDataSetup extends TestSetup with WithProbes with AutomaticHpackWireSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        val entityDataOut = TestPublisher.probe[ByteString]()
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(1, response)
        expectResponseHEADERS(streamId = 1, endStream = false) shouldBe response.withEntity(HttpEntity.Empty)
      }

      "send entity data as data frames" in new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(1, endStream = false, data1)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)
        expectDATA(1, endStream = false, data2)

        entityDataOut.sendComplete()
        expectDATA(1, endStream = true, ByteString.empty)
      }
      "cancel entity data source when peer sends RST_STREAM" inPendingUntilFixed new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(1, endStream = false, data1)

        sendRST_STREAM(1, ErrorCode.CANCEL)
        entityDataOut.expectCancellation()
      }

      "send RST_STREAM when entity data stream fails" inPendingUntilFixed new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(1, endStream = false, data1)

        entityDataOut.sendError(new RuntimeException)
        expectRST_STREAM(1, ErrorCode.INTERNAL_ERROR)
      }
      "fail if advertised content-length is exceed" in pending
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" in pending
      "send two responses concurrently" in new TestSetup with WithProbes with AutomaticHpackWireSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        val entity1DataOut = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity1DataOut)))
        emitResponse(1, response1)
        expectResponseHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty)

        def sendDataAndExpectOnNet(outStream: TestPublisher.Probe[ByteString], streamId: Int, dataString: String, endStream: Boolean = false): Unit = {
          val data = ByteString(dataString)
          if (dataString.nonEmpty) outStream.sendNext(data)
          if (endStream) outStream.sendComplete()
          if (data.nonEmpty || endStream) expectDATA(streamId, endStream = endStream, data)
        }

        sendDataAndExpectOnNet(entity1DataOut, 1, "abc")

        // send second request
        sendRequest(3, theRequest)
        expectRequest() shouldBe theRequest

        val entity2DataOut = TestPublisher.probe[ByteString]()
        val response2 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity2DataOut)))
        emitResponse(3, response2)
        expectResponseHEADERS(streamId = 3, endStream = false) shouldBe response2.withEntity(HttpEntity.Empty)

        // send again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "zyx")

        // now send on stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "mnopq")

        // now again on stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "jklm")

        // last data of stream 2
        sendDataAndExpectOnNet(entity2DataOut, 3, "uvwx", endStream = true)

        // also complete stream 1
        sendDataAndExpectOnNet(entity1DataOut, 1, "", endStream = true)
      }
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

    def expectDATAFrame(streamId: Int): (Boolean, ByteString) = {
      val (flags, payload) = expectFrameFlagsAndPayload(FrameType.DATA, streamId)
      (Flags.END_STREAM.isSet(flags), payload)
    }

    def expectDATA(streamId: Int, endStream: Boolean, numBytes: Int): ByteString = {
      @tailrec def collectMore(collected: ByteString, remainingBytes: Int): ByteString = {
        val (completed, data) = expectDATAFrame(streamId)
        data.size should be <= remainingBytes // cannot have more data pending
        if (data.size < remainingBytes) {
          completed shouldBe false
          collectMore(collected ++ data, remainingBytes - data.size)
        } else {
          // data.size == remainingBytes, i.e. collection finished
          if (endStream && !completed) // wait for final empty data frame
            expectFramePayload(FrameType.DATA, Flags.END_STREAM, streamId) shouldBe ByteString.empty
          collected ++ data
        }
      }
      collectMore(ByteString.empty, numBytes)
    }

    def expectDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit =
      expectDATA(streamId, endStream, data.length) shouldBe data

    def expectRST_STREAM(streamId: Int, errorCode: ErrorCode): Unit =
      expectRST_STREAM(streamId) shouldBe errorCode

    def expectRST_STREAM(streamId: Int): ErrorCode = {
      val payload = expectFramePayload(FrameType.RST_STREAM, ByteFlag.Zero, streamId)
      ErrorCode.byId(new ByteReader(payload).readIntBE())
    }

    def expectFrameFlagsAndPayload(frameType: FrameType, streamId: Int): (ByteFlag, ByteString) = {
      val header = expectFrameHeader()
      header.frameType shouldBe frameType

      header.streamId shouldBe streamId
      val data = expectBytes(header.payloadLength)
      (header.flags, data)
    }
    def expectFramePayload(frameType: FrameType, expectedFlags: ByteFlag, streamId: Int): ByteString = {
      val (flags, data) = expectFrameFlagsAndPayload(frameType, streamId)
      expectedFlags shouldBe flags
      data
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
    def expectHeaderBlock(streamId: Int, endStream: Boolean = true): ByteString =
      // FIXME: also collect CONTINUATION frames as long as END_HEADERS is not set
      expectFramePayload(FrameType.HEADERS, Flags.END_STREAM.ifSet(endStream) | Flags.END_HEADERS, streamId)

    def sendFrame(frameType: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): Unit =
      sendBytes(FrameRenderer.renderFrame(frameType, flags, streamId, payload))

    def sendHEADERS(streamId: Int, endStream: Boolean, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment)))
    def sendCONTINUATION(streamId: Int, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(ContinuationFrame(streamId, endHeaders, headerBlockFragment)))

    def sendRST_STREAM(streamId: Int, errorCode: ErrorCode): Unit = {
      implicit val bigEndian = ByteOrder.BIG_ENDIAN
      val bb = new ByteStringBuilder
      bb.putInt(errorCode.id)
      sendFrame(FrameType.RST_STREAM, ByteFlag.Zero, streamId, ByteString.empty)
    }
  }
  case class FrameHeader(frameType: FrameType, flags: ByteFlag, streamId: Int, payloadLength: Int)
  abstract class TestSetup extends TestSetupWithoutHandshake {
    sendBytes(Http2Protocol.ClientConnectionPreface)
    val serverPreface = expectFrameHeader()
    serverPreface.frameType shouldBe Http2Protocol.FrameType.SETTINGS
  }

  /** Helper that allows automatic HPACK encoding/decoding for wire sends / expectations */
  trait AutomaticHpackWireSupport extends TestSetupWithoutHandshake {
    def sendRequest(streamId: Int, request: HttpRequest): Unit = {
      require(request.entity.isKnownEmpty, "Only empty entities supported for `sendRequest`")
      sendHEADERS(streamId, endStream = true, endHeaders = true, encodeHeaders(request))
    }
    def expectResponseHEADERS(streamId: Int, endStream: Boolean = true): HttpResponse = {
      val headerBlockBytes = expectHeaderBlock(streamId, endStream)
      decodeHeaders(headerBlockBytes)
    }

    val encoder = new Encoder(HttpRequestHeaderHpackDecompression.maxHeaderTableSize)
    def encodeHeaders(request: HttpRequest): ByteString = {
      val bos = new ByteArrayOutputStream()
      def encode(name: String, value: String): Unit = encoder.encodeHeader(bos, name.getBytes, value.getBytes, false)
      encode(":method", request.method.value)
      encode(":scheme", request.uri.scheme.toString)
      encode(":path", request.uri.path.toString)
      encode(":authority", request.uri.authority.toString)

      request.headers.filter(_.renderInRequests()).foreach { h ⇒
        encode(h.lowercaseName, h.value)
      }

      ByteString(bos.toByteArray)
    }

    val decoder = new Decoder(HttpRequestHeaderHpackDecompression.maxHeaderSize, HttpRequestHeaderHpackDecompression.maxHeaderTableSize)
    def decodeHeaders(bytes: ByteString): HttpResponse = {
      val bis = new ByteArrayInputStream(bytes.toArray)
      var response = HttpResponse()
      def update(f: HttpResponse ⇒ HttpResponse): Unit = response = f(response)
      decoder.decode(bis, new HeaderListener {
        def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit =
          new String(name) match {
            case ":status" ⇒ update(_.copy(status = new String(value).toInt))
            case x         ⇒ update(_.addHeader(RawHeader(x, new String(value)))) // FIXME: decode to modeled headers
          }
      })
      response
    }
  }

  trait WithProbes extends TestSetupWithoutHandshake {
    lazy val requestIn = TestSubscriber.probe[HttpRequest]()
    lazy val responseOut = TestPublisher.probe[HttpResponse]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow.fromSinkAndSource(Sink.fromSubscriber(requestIn), Source.fromPublisher(responseOut))

    def expectRequest(): HttpRequest = requestIn.requestNext().removeHeader("x-http2-stream-id")
    def emitResponse(streamId: Int, response: HttpResponse): Unit =
      responseOut.sendNext(response.addHeader(Http2StreamIdHeader(streamId)))
  }
  trait WithHandler extends TestSetupWithoutHandshake {
    def parallelism: Int = 2
    def handler: HttpRequest ⇒ Future[HttpResponse] =
      _ ⇒ Future.successful(HttpResponse())

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)
  }
}
