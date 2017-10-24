package akka.http.impl.engine.http2

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }
import java.net.{ InetAddress, InetSocketAddress }
import java.nio.ByteOrder

import akka.NotUsed
import akka.http.impl.engine.http2.Http2Protocol.{ ErrorCode, Flags, FrameType, SettingIdentifier }
import akka.http.impl.engine.http2.framing.FrameRenderer
import akka.http.impl.engine.server.HttpAttributes
import akka.http.impl.engine.ws.ByteStringSinkProbe
import akka.http.impl.util.{ StreamUtils, StringRendering }
import akka.http.scaladsl.Http.ServerLayer
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ CacheDirectives, RawHeader, `Remote-Address` }
import akka.http.scaladsl.model.http2.Http2StreamIdHeader
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.impl.io.ByteStringParser.ByteReader
import akka.stream.scaladsl.{ BidiFlow, Flow, Sink, Source }
import akka.stream.testkit.TestPublisher.ManualProbe
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.testkit._
import akka.util.{ ByteString, ByteStringBuilder }
import com.twitter.hpack.{ Decoder, Encoder, HeaderListener }
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class Http2ServerSpec extends AkkaSpec("""
    akka.loglevel = debug
    
    akka.http.server.remote-address-header = on
  """)
  with WithInPendingUntilFixed with Eventually {
  implicit val mat = ActorMaterializer()

  "The Http/2 server implementation" should {
    "support simple round-trips" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with RequestResponseProbes {
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
      "GOAWAY when invalid headers frame" in new TestSetup with RequestResponseProbes {
        override def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
          Flow[HttpRequest].map { req ⇒
            HttpResponse(entity = req.entity).addHeader(req.header[Http2StreamIdHeader].get)
          }

        val headerBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendHEADERS(1, endStream = false, endHeaders = true, headerBlockFragment = headerBlock)

        val (lastStreamId, errorCode) = expectGOAWAY(0) // since we have not processed any stream
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
      "GOAWAY when second request on different stream has invalid headers frame" in new SimpleRequestResponseRoundtripSetup {
        requestResponseRoundtrip(
          streamId = 1,
          requestHeaderBlock = HPackSpecExamples.C41FirstRequestWithHuffman,
          expectedRequest = HttpRequest(HttpMethods.GET, "http://www.example.com/", protocol = HttpProtocols.`HTTP/2.0`),
          response = HPackSpecExamples.FirstResponse,
          expectedResponseHeaderBlock = HPackSpecExamples.C61FirstResponseWithHuffman
        )

        // example from: https://github.com/summerwind/h2spec/blob/master/4_3.go#L18
        val incorrectHeaderBlock = hex"00 00 01 01 05 00 00 00 01 40"
        sendHEADERS(3, endStream = false, endHeaders = true, headerBlockFragment = incorrectHeaderBlock)

        val (lastStreamId, errorCode) = expectGOAWAY(1) // since we have sucessfully started processing stream `1`
        errorCode should ===(ErrorCode.COMPRESSION_ERROR)
      }
      "Three consecutive GET requests" in new SimpleRequestResponseRoundtripSetup {
        import CacheDirectives._
        import headers.`Cache-Control`
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
            headers = Vector(`Cache-Control`(`no-cache`)),
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
      "GET request in one HEADERS and one CONTINUATION frame" in new TestSetup with RequestResponseProbes {
        val headerBlock = HPackSpecExamples.C41FirstRequestWithHuffman
        val fragment1 = headerBlock.take(8) // must be grouped by octets
        val fragment2 = headerBlock.drop(8)

        sendHEADERS(1, endStream = true, endHeaders = false, fragment1)
        requestIn.ensureSubscription()
        requestIn.expectNoMsg()
        sendCONTINUATION(1, endHeaders = true, fragment2)

        val request = expectRequestRaw()

        request.method shouldBe HttpMethods.GET
        request.uri shouldBe Uri("http://www.example.com/")

        val streamIdHeader = request.header[Http2StreamIdHeader].getOrElse(Http2Compliance.missingHttpIdHeaderException)
        responseOut.sendNext(HPackSpecExamples.FirstResponse.addHeader(streamIdHeader))
        val headerPayload = expectHeaderBlock(1)
        headerPayload shouldBe HPackSpecExamples.C61FirstResponseWithHuffman
      }

      "fail if Http2StreamIdHeader missing" in pending
      "automatically add `Date` header" in pending

      "parse headers to modeled headers" in new TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        import CacheDirectives._
        import headers._
        val expectedRequest = HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(`Cache-Control`(`no-cache`), `Cache-Control`(`max-age`(1000)), `Access-Control-Allow-Origin`.`*`),
          protocol = HttpProtocols.`HTTP/2.0`)
        val streamId = 1
        val requestHeaderBlock = encodeRequestHeaders(HttpRequest(
          method = HttpMethods.GET,
          uri = "http://www.example.com/",
          headers = Vector(
            RawHeader("cache-control", "no-cache"),
            RawHeader("cache-control", "max-age=1000"),
            RawHeader("access-control-allow-origin", "*")),
          protocol = HttpProtocols.`HTTP/2.0`))
        sendHEADERS(streamId, endStream = true, endHeaders = true, requestHeaderBlock)
        expectRequest().headers shouldBe expectedRequest.headers
      }
    }

    "support stream for request entity data" should {
      abstract class RequestEntityTestSetup extends TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        val TheStreamId = 1
        protected def sendRequest(): Unit

        sendRequest()
        val receivedRequest = expectRequest()
        val entityDataIn = ByteStringSinkProbe()
        receivedRequest.entity.dataBytes.runWith(entityDataIn.sink)
        entityDataIn.ensureSubscription()
      }

      abstract class WaitingForRequest(request: HttpRequest) extends RequestEntityTestSetup {
        protected def sendRequest(): Unit = sendRequest(TheStreamId, request)
      }
      abstract class WaitingForRequestData extends RequestEntityTestSetup {
        lazy val request = HttpRequest(method = HttpMethods.POST, uri = "https://example.com/upload", protocol = HttpProtocols.`HTTP/2.0`)

        protected def sendRequest(): Unit =
          sendRequestHEADERS(TheStreamId, request, endStream = false)

        def sendWindowFullOfData(): Int = {
          val dataLength = remainingToServerWindowFor(TheStreamId)
          sendDATA(TheStreamId, endStream = false, ByteString(Array.fill[Byte](dataLength)(23)))
          dataLength
        }
      }
      "send data frames to entity stream" in new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        val data2 = ByteString("zyxwvu")
        sendDATA(TheStreamId, endStream = false, data2)
        entityDataIn.expectBytes(data2)

        val data3 = ByteString("mnopq")
        sendDATA(TheStreamId, endStream = true, data3)
        entityDataIn.expectBytes(data3)
        entityDataIn.expectComplete()
      }
      "handle content-length and content-type of incoming request" in new WaitingForRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = "https://example.com/upload",
          entity = HttpEntity(ContentTypes.`application/json`, 1337, Source.repeat("x").take(1337).map(ByteString(_))),
          protocol = HttpProtocols.`HTTP/2.0`)) {

        receivedRequest.entity.contentType should ===(ContentTypes.`application/json`)
        receivedRequest.entity.isIndefiniteLength should ===(false)
        receivedRequest.entity.contentLengthOption should ===(Some(1337L))
        entityDataIn.expectBytes(ByteString("x" * 1337))
        entityDataIn.expectComplete()
      }
      "fail entity stream if peer sends RST_STREAM frame" in new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        sendRST_STREAM(TheStreamId, ErrorCode.INTERNAL_ERROR)
        val error = entityDataIn.expectError()
        error.getMessage shouldBe "Stream with ID [1] was closed by peer with code INTERNAL_ERROR(0x02)"
      }
      "send RST_STREAM if entity stream is canceled" in new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        pollForWindowUpdates(10.millis)

        entityDataIn.cancel()
        expectRST_STREAM(TheStreamId, ErrorCode.CANCEL)
      }
      "send out WINDOW_UPDATE frames when request data is read so that the stream doesn't stall" in new WaitingForRequestData {
        (1 to 10).foreach { _ ⇒
          val bytesSent = sendWindowFullOfData()
          bytesSent should be > 0
          entityDataIn.expectBytes(bytesSent)
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) should be > 0
        }
      }
      "backpressure until request entity stream is read (don't send out unlimited WINDOW_UPDATE before)" in new WaitingForRequestData {
        var totallySentBytes = 0
        // send data until we don't receive any window updates from the implementation any more
        eventually(Timeout(1.second.dilated)) {
          totallySentBytes += sendWindowFullOfData()
          // the implementation may choose to send a few window update until internal buffers are filled
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) shouldBe 0
        }

        // now drain entity source
        entityDataIn.expectBytes(totallySentBytes)

        eventually(Timeout(1.second.dilated)) {
          pollForWindowUpdates(10.millis)
          remainingToServerWindowFor(TheStreamId) should be > 0
        }
      }
      "send data frames to entity stream and ignore trailing headers" in new WaitingForRequestData {
        val data1 = ByteString("abcdef")
        sendDATA(TheStreamId, endStream = false, data1)
        entityDataIn.expectBytes(data1)

        sendHEADERS(TheStreamId, endStream = true, Seq(headers.`Cache-Control`(CacheDirectives.`no-cache`)))
        entityDataIn.expectComplete()
      }

      "fail entity stream if advertised content-length doesn't match" in pending
    }

    "support stream support for sending response entity data" should {
      abstract class WaitingForResponseSetup extends TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        val TheStreamId = 1
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(TheStreamId, theRequest)
        expectRequest() shouldBe theRequest
      }
      abstract class WaitingForResponseDataSetup extends WaitingForResponseSetup {
        val entityDataOut = TestPublisher.probe[ByteString]()

        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(TheStreamId, response)
        expectDecodedResponseHEADERS(streamId = TheStreamId, endStream = false) shouldBe response.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))
      }

      "encode Content-Length and Content-Type headers" in new WaitingForResponseSetup {
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, ByteString("abcde")))
        emitResponse(TheStreamId, response)
        val pairs = expectDecodedResponseHEADERSPairs(streamId = TheStreamId, endStream = false).toMap
        pairs should contain(":status" → "200")
        pairs should contain("content-length" → "5")
        pairs should contain("content-type" → "application/octet-stream")
      }

      "send entity data as data frames" in new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        val data2 = ByteString("efghij")
        entityDataOut.sendNext(data2)
        expectDATA(TheStreamId, endStream = false, data2)

        entityDataOut.sendComplete()
        expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "parse priority frames" in new WaitingForResponseDataSetup {
        sendPRIORITY(TheStreamId, true, 0, 5)
        entityDataOut.sendComplete()
        expectDATA(TheStreamId, endStream = true, ByteString.empty)
      }

      "cancel entity data source when peer sends RST_STREAM" in new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        sendRST_STREAM(TheStreamId, ErrorCode.CANCEL)
        entityDataOut.expectCancellation()
      }
      "cancel entity data source when peer sends RST_STREAM before entity is subscribed" in new TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        sendRST_STREAM(1, ErrorCode.CANCEL)

        val entityDataOut = TestPublisher.probe[ByteString]()
        val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entityDataOut)))
        emitResponse(1, response)
        expectNoBytes() // don't expect response on closed connection
        entityDataOut.expectCancellation()
      }

      "send RST_STREAM when entity data stream fails" in new WaitingForResponseDataSetup {
        val data1 = ByteString("abcd")
        entityDataOut.sendNext(data1)
        expectDATA(TheStreamId, endStream = false, data1)

        entityDataOut.sendError(new RuntimeException with NoStackTrace)
        expectRST_STREAM(1, ErrorCode.INTERNAL_ERROR)
      }
      "fail if advertised content-length doesn't match" in pending

      "send data frame with exactly the number of remaining connection-level window bytes even when chunk is bigger than that" in new WaitingForResponseDataSetup {
        // otherwise, the stream may stall if the client doesn't send another window update (which it isn't required to do
        // unless a window falls to 0)

        entityDataOut.sendNext(bytes(70000, 0x23)) // 70000 > Http2Protocol.InitialWindowSize
        expectDATA(TheStreamId, false, Http2Protocol.InitialWindowSize)

        expectNoBytes()

        sendWINDOW_UPDATE(TheStreamId, 10000) // > than the remaining bytes (70000 - InitialWindowSize)
        sendWINDOW_UPDATE(0, 10000)

        expectDATA(TheStreamId, false, 70000 - Http2Protocol.InitialWindowSize)
      }

      "backpressure response entity stream until WINDOW_UPDATE was received" in new WaitingForResponseDataSetup {
        var totalSentBytes = 0
        var totalReceivedBytes = 0

        entityDataOut.ensureSubscription()

        def receiveData(maxBytes: Int = Int.MaxValue): Unit = {
          val (false, data) = expectDATAFrame(TheStreamId)
          totalReceivedBytes += data.size
        }

        def sendAWindow(): Unit = {
          val window = remainingFromServerWindowFor(TheStreamId)
          val dataToSend = window max 60000 // send at least 10 bytes
          entityDataOut.sendNext(bytes(dataToSend, 0x42))
          totalSentBytes += dataToSend

          if (window > 0) receiveData(window)
        }

        /**
         * Loop that checks for a while that publisherProbe has outstanding demand and runs body to fulfill it
         * Will fail if there's still demand after the timeout.
         */
        def fulfillDemandWithin(publisherProbe: TestPublisher.Probe[_], timeout: FiniteDuration)(body: ⇒ Unit): Unit = {
          // HACK to support `expectRequest` with a timeout
          def within[T](publisherProbe: TestPublisher.Probe[_], dur: FiniteDuration)(t: ⇒ T): T = {
            val field = classOf[ManualProbe[_]].getDeclaredField("probe")
            field.setAccessible(true)
            field.get(publisherProbe).asInstanceOf[TestProbe].within(dur)(t)
          }
          def expectRequest(timeout: FiniteDuration): Long =
            within(publisherProbe, timeout)(publisherProbe.expectRequest())

          eventually(Timeout(timeout)) {
            while (publisherProbe.pending > 0) body

            try expectRequest(10.millis.dilated)
            catch {
              case ex: Throwable ⇒ // ignore error here
            }
            publisherProbe.pending shouldBe 0 // fail here if there's still demand after the timeout
          }
        }

        fulfillDemandWithin(entityDataOut, 3.seconds.dilated)(sendAWindow())

        sendWINDOW_UPDATE(TheStreamId, totalSentBytes)
        sendWINDOW_UPDATE(0, totalSentBytes)

        while (totalReceivedBytes < totalSentBytes)
          receiveData()

        // we must get at least a bit of demand
        entityDataOut.sendNext(bytes(1000, 0x23))
      }
      "give control frames priority over pending data frames" in new WaitingForResponseDataSetup {
        val responseDataChunk = bytes(1000, 0x42)

        // send data first but expect it to be queued because of missing demand
        entityDataOut.sendNext(responseDataChunk)

        // no receive a PING frame which should be answered with a PING(ack = true) frame
        val pingData = bytes(8, 0x23)
        sendFrame(PingFrame(ack = false, pingData))

        // now expect PING ack frame to "overtake" the data frame
        expectFrame(FrameType.PING, Flags.ACK, 0, pingData)
        expectDATA(TheStreamId, endStream = false, responseDataChunk)
      }
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" in new TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        val request1 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        sendRequestHEADERS(1, request1, endStream = false)

        val gotRequest1 = expectRequest()
        gotRequest1.withEntity(HttpEntity.Empty) shouldBe request1.withEntity(HttpEntity.Empty)
        val request1EntityProbe = ByteStringSinkProbe()
        gotRequest1.entity.dataBytes.runWith(request1EntityProbe.sink)

        val request2 =
          HttpRequest(
            protocol = HttpProtocols.`HTTP/2.0`,
            entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))

        sendRequestHEADERS(3, request2, endStream = false)

        val gotRequest2 = expectRequest()
        gotRequest2.withEntity(HttpEntity.Empty) shouldBe request2.withEntity(HttpEntity.Empty)
        val request2EntityProbe = ByteStringSinkProbe()
        gotRequest2.entity.dataBytes.runWith(request2EntityProbe.sink)

        sendDATA(3, endStream = false, ByteString("abc"))
        request2EntityProbe.expectUtf8EncodedString("abc")

        sendDATA(1, endStream = false, ByteString("def"))
        request1EntityProbe.expectUtf8EncodedString("def")

        // now fail stream 2
        //sendRST_STREAM(3, ErrorCode.INTERNAL_ERROR)
        //request2EntityProbe.expectError()

        // make sure that other stream is not affected
        sendDATA(1, endStream = true, ByteString("ghi"))
        request1EntityProbe.expectUtf8EncodedString("ghi")
        request1EntityProbe.expectComplete()
      }
      "send two responses concurrently" in new TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {
        val theRequest = HttpRequest(protocol = HttpProtocols.`HTTP/2.0`)
        sendRequest(1, theRequest)
        expectRequest() shouldBe theRequest

        val entity1DataOut = TestPublisher.probe[ByteString]()
        val response1 = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.fromPublisher(entity1DataOut)))
        emitResponse(1, response1)
        expectDecodedResponseHEADERS(streamId = 1, endStream = false) shouldBe response1.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

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
        expectDecodedResponseHEADERS(streamId = 3, endStream = false) shouldBe response2.withEntity(HttpEntity.Empty.withContentType(ContentTypes.`application/octet-stream`))

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

    "respect flow-control" should {
      "not exceed connection-level window while sending" in pending
      "not exceed stream-level window while sending" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed when window became negative through setting" in pending

      "eventually send WINDOW_UPDATE frames for received data" in pending

      "reject WINDOW_UPDATE for connection with zero increment with PROTOCOL_ERROR" in new TestSetup with RequestResponseProbes {
        sendWINDOW_UPDATE(0, 0) // illegal
        val (_, errorCode) = expectGOAWAY()

        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "reject WINDOW_UPDATE for stream with zero increment with PROTOCOL_ERROR" in new TestSetup with RequestResponseProbes {
        // making sure we don't handle stream 0 and others differently here
        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendWINDOW_UPDATE(1, 0) // illegal

        expectRST_STREAM(1, ErrorCode.PROTOCOL_ERROR)
      }

    }

    "respect settings" should {
      "initial MAX_FRAME_SIZE" in pending

      "received non-zero length payload Settings with ACK flag (invalid 6.5)" in new TestSetup with RequestResponseProbes {
        /*
         Receipt of a SETTINGS frame with the ACK flag set and a length field value other than 0
         MUST be treated as a connection error (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        // we ACK the settings with an incorrect ACK (it must not have a payload)
        val ackFlag = new ByteFlag(0x1)
        val illegalPayload = hex"cafe babe"
        sendFrame(FrameType.SETTINGS, ackFlag, 0, illegalPayload)

        val (lastStreamId, error) = expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }
      "received SETTINGs frame frame with a length other than a multiple of 6 octets (invalid 6_5)" in new TestSetup with RequestResponseProbes {
        val data = hex"00 00 02 04 00 00 00 00 00"

        sendFrame(FrameType.SETTINGS, ByteFlag.Zero, 0, data)

        val (lastStreamId, error) = expectGOAWAY()
        error should ===(ErrorCode.FRAME_SIZE_ERROR)
      }

      "received SETTINGS_MAX_FRAME_SIZE should cause outgoing DATA to be chunked up into at-most-that-size parts " in new TestSetup with RequestResponseProbes {
        val maxSize = Math.pow(2, 15).toInt // 32768, valid value (between 2^14 and 2^24 - 1)
        sendSETTING(SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, maxSize)

        expectSettingsAck()

        sendWINDOW_UPDATE(0, maxSize * 10) // make sure we can receive such large response, on connection

        sendHEADERS(1, endStream = false, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendWINDOW_UPDATE(1, maxSize * 5) // make sure we can receive such large response, on this stream

        val theTooLargeByteString = ByteString("x" * (maxSize * 2))
        val tooLargeEntity = Source.single(theTooLargeByteString)
        val tooLargeResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/octet-stream`, tooLargeEntity))
        emitResponse(1, tooLargeResponse)

        expectHeaderBlock(1, endStream = false)
        // we receive the DATA in 2 parts, since the ByteString does not fit in a single frame
        val d1 = expectDATA(1, endStream = false, numBytes = maxSize)
        val d2 = expectDATA(1, endStream = true, numBytes = maxSize)
        d1.toList should have length maxSize
        d2.toList should have length maxSize
        (d1 ++ d2) should ===(theTooLargeByteString) // makes sure we received the parts in the right order
      }

      "received SETTINGS_MAX_CONCURRENT_STREAMS should limit the number of streams" in new TestSetup with RequestResponseProbes {
        sendSETTING(SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, 1)
        expectSettingsAck()

        // TODO actually apply the limiting and verify it works
      }

      "received SETTINGS_HEADER_TABLE_SIZE" in new TestSetup with RequestResponseProbes {
        sendSETTING(SettingIdentifier.SETTINGS_HEADER_TABLE_SIZE, Math.pow(2, 15).toInt) // 32768, valid value (between 2^14 and 2^24 - 1)

        expectSettingsAck() // TODO check that the setting was indeed applied
      }

      "react on invalid SETTINGS_INITIAL_WINDOW_SIZE with FLOW_CONTROL_ERROR" in new TestSetup with RequestResponseProbes {
        // valid values are below 2^31 - 1 for int, which actually just means positive
        sendSETTING(SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, -1)

        val (_, code) = expectGOAWAY()
        code should ===(ErrorCode.FLOW_CONTROL_ERROR)
      }
    }

    "support low-level features" should {
      "respond to PING frames (spec 6_7)" in new TestSetup with RequestResponseProbes {
        sendFrame(FrameType.PING, new ByteFlag(0x0), 0, ByteString("data1234")) // ping frame data must be of size 8

        val (flag, payload) = expectFrameFlagsAndPayload(FrameType.PING, 0) // must be on stream 0
        flag should ===(new ByteFlag(0x1)) // a PING response
        payload should ===(ByteString("data1234"))
      }
      "NOT respond to PING ACK frames (spec 6_7)" in new TestSetup with RequestResponseProbes {
        val AckFlag = new ByteFlag(0x1)
        sendFrame(FrameType.PING, AckFlag, 0, ByteString("data1234"))

        expectNoMsg(100 millis)
      }
      "respond to invalid (not 0x0 streamId) PING with GOAWAY (spec 6_7)" in new TestSetup with RequestResponseProbes {
        val invalidIdForPing = 1
        sendFrame(FrameType.PING, ByteFlag.Zero, invalidIdForPing, ByteString("abcd1234"))

        val (lastStreamId, errorCode) = expectGOAWAY()
        errorCode should ===(ErrorCode.PROTOCOL_ERROR)
      }
      "respond to PING frames giving precedence over any other kind pending frame" in pending
      "acknowledge SETTINGS frames" in pending
    }

    "respect the substream state machine" should {
      abstract class SimpleRequestResponseRoundtripSetup extends TestSetup with RequestResponseProbes

      "reject other frame than HEADERS/PUSH_PROMISE in idle state with connection-level PROTOCOL_ERROR (5.1)" inPendingUntilFixed new SimpleRequestResponseRoundtripSetup {
        sendDATA(9, endStream = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }
      "reject incoming frames on already half-closed substream" in pending

      "reject even-numbered client-initiated substreams" inPendingUntilFixed new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(2, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }

      "reject all other frames while waiting for CONTINUATION frames" in pending

      "reject double sub-streams creation" inPendingUntilFixed new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }
      "reject substream creation for streams invalidated by skipped substream IDs" inPendingUntilFixed new SimpleRequestResponseRoundtripSetup {
        sendHEADERS(9, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        sendHEADERS(1, endStream = true, endHeaders = true, HPackSpecExamples.C41FirstRequestWithHuffman)
        expectGOAWAY()
        // after GOAWAY we expect graceful completion after x amount of time
        // TODO: completion logic, wait?!
        expectGracefulCompletion()
      }
    }

    "expose synthetic headers" should {
      "expose Remote-Address" in new TestSetup with RequestResponseProbes with AutomaticHpackWireSupport {

        lazy val theAddress = "127.0.0.1"
        lazy val thePort = 1337
        override def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]) =
          BidiFlow.fromGraph(StreamUtils.fuseAggressive(server).withAttributes(
            HttpAttributes.remoteAddress(new InetSocketAddress(theAddress, thePort))
          ))

        val target = Uri("http://www.example.com/")
        sendRequest(1, HttpRequest(uri = target))
        requestIn.ensureSubscription()

        val request = expectRequestRaw()
        val remoteAddressHeader = request.header[headers.`Remote-Address`].get
        remoteAddressHeader.address.getAddress.get().toString shouldBe ("/" + theAddress)
        remoteAddressHeader.address.getPort shouldBe thePort
      }

    }

    "must not swallow errors / warnings" in pending
  }

  protected /* To make ByteFlag warnings go away */ abstract class TestSetupWithoutHandshake {
    implicit def ec = system.dispatcher

    val toNet = ByteStringSinkProbe()
    val fromNet = TestPublisher.probe[ByteString]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed]

    // hook to modify server, for example add attributes
    def modifyServer(server: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed]) = server

    final def theServer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
      modifyServer(Http2Blueprint.serverStack(ServerSettings(system).withServerHeader(None), system.log))

    handlerFlow
      .join(theServer)
      .runWith(Source.fromPublisher(fromNet), toNet.sink)

    def sendBytes(bytes: ByteString): Unit = fromNet.sendNext(bytes)
    def expectBytes(bytes: ByteString): Unit = toNet.expectBytes(bytes)
    def expectBytes(num: Int): ByteString = toNet.expectBytes(num)
    def expectNoBytes(): Unit = toNet.expectNoBytes()

    def expectDATAFrame(streamId: Int): (Boolean, ByteString) = {
      val (flags, payload) = expectFrameFlagsAndPayload(FrameType.DATA, streamId)
      updateFromServerWindowForConnection(_ - payload.size)
      updateFromServerWindows(streamId, _ - payload.size)
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

    def autoFrameHandler: PartialFunction[FrameHeader, Unit] = {
      case FrameHeader(FrameType.WINDOW_UPDATE, _, streamId, payloadLength) ⇒
        val data = expectBytes(payloadLength)
        val windowSizeIncrement = new ByteReader(data).readIntBE()

        if (streamId == 0) updateToServerWindowForConnection(_ + windowSizeIncrement)
        else updateToServerWindows(streamId, _ + windowSizeIncrement)
    }

    /**
     * If the lastStreamId should not be asserted keep it as a negative value (which is never a real stream id)
     * @return pair of `lastStreamId` and the [[ErrorCode]]
     */
    def expectGOAWAY(lastStreamId: Int = -1): (Int, ErrorCode) = {
      // GOAWAY is always written to stream zero:
      //   The GOAWAY frame applies to the connection, not a specific stream.
      //   An endpoint MUST treat a GOAWAY frame with a stream identifier other than 0x0
      //   as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
      val payload = expectFramePayload(FrameType.GOAWAY, ByteFlag.Zero, streamId = 0)
      val reader = new ByteReader(payload)
      val incomingLastStreamId = reader.readIntBE()
      if (lastStreamId > 0) incomingLastStreamId should ===(lastStreamId)
      (lastStreamId, ErrorCode.byId(reader.readIntBE()))
    }

    def expectSettingsAck() = expectFrame(FrameType.SETTINGS, Flags.ACK, 0, ByteString.empty)

    def expectFrame(frameType: FrameType, expectedFlags: ByteFlag, streamId: Int, payload: ByteString) =
      expectFramePayload(frameType, expectedFlags, streamId) should ===(payload)

    def expectFramePayload(frameType: FrameType, expectedFlags: ByteFlag, streamId: Int): ByteString = {
      val (flags, data) = expectFrameFlagsAndPayload(frameType, streamId)
      expectedFlags shouldBe flags
      data
    }
    final def expectFrameFlagsAndPayload(frameType: FrameType, streamId: Int): (ByteFlag, ByteString) = {
      val (flags, gotStreamId, data) = expectFrameFlagsStreamIdAndPayload(frameType)
      gotStreamId shouldBe streamId
      (flags, data)
    }
    final def expectFrameFlagsStreamIdAndPayload(frameType: FrameType): (ByteFlag, Int, ByteString) = {
      val header = expectFrameHeader()
      header.frameType shouldBe frameType
      (header.flags, header.streamId, expectBytes(header.payloadLength))
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

    def sendFrame(frame: FrameEvent): Unit =
      sendBytes(FrameRenderer.render(frame))

    def sendFrame(frameType: FrameType, flags: ByteFlag, streamId: Int, payload: ByteString): Unit =
      sendBytes(FrameRenderer.renderFrame(frameType, flags, streamId, payload))

    def sendDATA(streamId: Int, endStream: Boolean, data: ByteString): Unit = {
      updateToServerWindowForConnection(_ - data.length)
      updateToServerWindows(streamId, _ - data.length)
      sendFrame(FrameType.DATA, Flags.END_STREAM.ifSet(endStream), streamId, data)
    }

    def sendSETTING(identifier: SettingIdentifier, value: Int): Unit =
      sendFrame(SettingsFrame(Setting(identifier, value) :: Nil))

    def sendHEADERS(streamId: Int, endStream: Boolean, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(HeadersFrame(streamId, endStream, endHeaders, headerBlockFragment, None)))

    def sendCONTINUATION(streamId: Int, endHeaders: Boolean, headerBlockFragment: ByteString): Unit =
      sendBytes(FrameRenderer.render(ContinuationFrame(streamId, endHeaders, headerBlockFragment)))

    def sendPRIORITY(streamId: Int, exclusiveFlag: Boolean, streamDependency: Int, weight: Int): Unit =
      sendBytes(FrameRenderer.render(PriorityFrame(streamId, exclusiveFlag, streamDependency, weight)))

    def sendRST_STREAM(streamId: Int, errorCode: ErrorCode): Unit = {
      implicit val bigEndian = ByteOrder.BIG_ENDIAN
      val bb = new ByteStringBuilder
      bb.putInt(errorCode.id)
      sendFrame(FrameType.RST_STREAM, ByteFlag.Zero, streamId, bb.result())
    }

    def sendWINDOW_UPDATE(streamId: Int, windowSizeIncrement: Int): Unit = {
      sendBytes(FrameRenderer.render(WindowUpdateFrame(streamId, windowSizeIncrement)))
      updateFromServerWindowForConnection(_ + windowSizeIncrement)
      updateFromServerWindows(streamId, _ + windowSizeIncrement)
    }

    def expectWindowUpdate(): Unit =
      expectFrameFlagsStreamIdAndPayload(FrameType.WINDOW_UPDATE) match {
        case (flags, streamId, payload) ⇒
          // TODO: DRY up with autoFrameHandler
          val windowSizeIncrement = new ByteReader(payload).readIntBE()

          if (streamId == 0) updateToServerWindowForConnection(_ + windowSizeIncrement)
          else updateToServerWindows(streamId, _ + windowSizeIncrement)
      }
    final def pollForWindowUpdates(duration: FiniteDuration): Unit =
      try {
        toNet.within(duration)(expectWindowUpdate())

        pollForWindowUpdates(duration)
      } catch {
        case e: AssertionError if e.getMessage contains "Expected OnNext(_), yet no element signaled during" ⇒
        // timeout, that's expected
      }

    // keep counters that are updated on outgoing sendDATA and incoming WINDOW_UPDATE frames
    private var toServerWindows = Map.empty[Int, Int].withDefaultValue(Http2Protocol.InitialWindowSize)
    private var toServerWindowForConnection = Http2Protocol.InitialWindowSize
    def remainingToServerWindowForConnection: Int = toServerWindowForConnection
    def remainingToServerWindowFor(streamId: Int): Int = toServerWindows(streamId) min remainingToServerWindowForConnection

    private var fromServerWindows = Map.empty[Int, Int].withDefaultValue(Http2Protocol.InitialWindowSize)
    private var fromServerWindowForConnection = Http2Protocol.InitialWindowSize
    // keep counters that are updated for incoming DATA frames and outgoing WINDOW_UPDATE frames
    def remainingFromServerWindowForConnection: Int = fromServerWindowForConnection
    def remainingFromServerWindowFor(streamId: Int): Int = fromServerWindows(streamId) min remainingFromServerWindowForConnection

    def updateWindowMap(streamId: Int, update: Int ⇒ Int): Map[Int, Int] ⇒ Map[Int, Int] =
      map ⇒ map.updated(streamId, update(map(streamId)))

    def safeUpdate(update: Int ⇒ Int): Int ⇒ Int = { oldValue ⇒
      val newValue = update(oldValue)
      newValue should be >= 0
      newValue
    }

    def updateToServerWindows(streamId: Int, update: Int ⇒ Int): Unit =
      toServerWindows = updateWindowMap(streamId, safeUpdate(update))(toServerWindows)
    def updateToServerWindowForConnection(update: Int ⇒ Int): Unit =
      toServerWindowForConnection = safeUpdate(update)(toServerWindowForConnection)

    def updateFromServerWindows(streamId: Int, update: Int ⇒ Int): Unit =
      fromServerWindows = updateWindowMap(streamId, safeUpdate(update))(fromServerWindows)
    def updateFromServerWindowForConnection(update: Int ⇒ Int): Unit =
      fromServerWindowForConnection = safeUpdate(update)(fromServerWindowForConnection)
  }
  case class FrameHeader(frameType: FrameType, flags: ByteFlag, streamId: Int, payloadLength: Int)

  /** Basic TestSetup that has already passed the exchange of the connection preface */
  abstract class TestSetup extends TestSetupWithoutHandshake {
    sendBytes(Http2Protocol.ClientConnectionPreface)
    val serverPreface = expectFrameHeader()
    serverPreface.frameType shouldBe Http2Protocol.FrameType.SETTINGS
  }

  /** Helper that allows automatic HPACK encoding/decoding for wire sends / expectations */
  trait AutomaticHpackWireSupport extends TestSetupWithoutHandshake {
    def sendRequestHEADERS(streamId: Int, request: HttpRequest, endStream: Boolean): Unit =
      sendHEADERS(streamId, endStream = endStream, endHeaders = true, encodeRequestHeaders(request))

    def sendHEADERS(streamId: Int, endStream: Boolean, headers: Seq[HttpHeader]): Unit =
      sendHEADERS(streamId, endStream = endStream, endHeaders = true, encodeHeaders(headers))

    def sendRequest(streamId: Int, request: HttpRequest)(implicit mat: Materializer): Unit = {
      val isEmpty = request.entity.isKnownEmpty
      sendHEADERS(streamId, endStream = isEmpty, endHeaders = true, encodeRequestHeaders(request))

      if (!isEmpty)
        sendDATA(streamId, endStream = true, request.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).futureValue)
    }

    def expectDecodedResponseHEADERS(streamId: Int, endStream: Boolean = true): HttpResponse = {
      val headerBlockBytes = expectHeaderBlock(streamId, endStream)
      val decoded = decodeHeadersToResponse(headerBlockBytes)
      // filter date to make it easier to test
      decoded.withHeaders(decoded.headers.filterNot(h ⇒ h.is("date")))
    }

    def expectDecodedResponseHEADERSPairs(streamId: Int, endStream: Boolean = true): Seq[(String, String)] = {
      val headerBlockBytes = expectHeaderBlock(streamId, endStream)
      decodeHeaders(headerBlockBytes)
    }

    val encoder = new Encoder(Http2Protocol.InitialMaxHeaderTableSize)

    def encodeRequestHeaders(request: HttpRequest): ByteString =
      encodeHeaderPairs(headerPairsForRequest(request))

    def encodeHeaders(headers: Seq[HttpHeader]): ByteString =
      encodeHeaderPairs(headerPairsForHeaders(headers))

    def headerPairsForRequest(request: HttpRequest): Seq[(String, String)] =
      Seq(
        ":method" → request.method.value,
        ":scheme" → request.uri.scheme.toString,
        ":path" → request.uri.path.toString,
        ":authority" → request.uri.authority.toString.drop(2),
        "content-type" → request.entity.contentType.render(new StringRendering).get
      ) ++
        request.entity.contentLengthOption.flatMap {
          case len if len != 0 ⇒ Some("content-length" → len.toString)
          case _               ⇒ None
        }.toSeq ++
        headerPairsForHeaders(request.headers.filter(_.renderInRequests))

    def headerPairsForHeaders(headers: Seq[HttpHeader]): Seq[(String, String)] =
      headers.map(h ⇒ h.lowercaseName → h.value)

    def encodeHeaderPairs(headerPairs: Seq[(String, String)]): ByteString = {
      val bos = new ByteArrayOutputStream()

      def encode(name: String, value: String): Unit = encoder.encodeHeader(bos, name.getBytes, value.getBytes, false)

      headerPairs.foreach((encode _).tupled)

      ByteString(bos.toByteArray)
    }

    val decoder = new Decoder(Http2Protocol.InitialMaxHeaderListSize, Http2Protocol.InitialMaxHeaderTableSize)

    def decodeHeaders(bytes: ByteString): Seq[(String, String)] = {
      val bis = new ByteArrayInputStream(bytes.toArray)
      val hs = new VectorBuilder[(String, String)]()

      decoder.decode(bis, new HeaderListener {
        def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit =
          hs += new String(name) → new String(value)
      })
      hs.result()
    }
    def decodeHeadersToResponse(bytes: ByteString): HttpResponse =
      decodeHeaders(bytes).foldLeft(HttpResponse())((old, header) ⇒ header match {
        case (":status", value)                             ⇒ old.copy(status = value.toInt)
        case ("content-length", value) if value.toLong == 0 ⇒ old.copy(entity = HttpEntity.Empty)
        case ("content-length", value)                      ⇒ old.copy(entity = HttpEntity.Default(old.entity.contentType, value.toLong, Source.empty))
        case ("content-type", value)                        ⇒ old.copy(entity = old.entity.withContentType(ContentType.parse(value).right.get))
        case (name, value)                                  ⇒ old.addHeader(RawHeader(name, value)) // FIXME: decode to modeled headers
      })
  }

  /** Provides the user handler flow as `requestIn` and `responseOut` probes for manual stream interaction */
  trait RequestResponseProbes extends TestSetupWithoutHandshake {
    lazy val requestIn = TestSubscriber.probe[HttpRequest]()
    lazy val responseOut = TestPublisher.probe[HttpResponse]()

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow.fromSinkAndSource(Sink.fromSubscriber(requestIn), Source.fromPublisher(responseOut))

    def expectRequest(): HttpRequest = requestIn.requestNext().removeHeader("x-http2-stream-id")
    def expectRequestRaw(): HttpRequest = requestIn.requestNext() // TODO, make it so that internal headers are not listed in `headers` etc?
    def emitResponse(streamId: Int, response: HttpResponse): Unit =
      responseOut.sendNext(response.addHeader(Http2StreamIdHeader(streamId)))

    def expectGracefulCompletion(): Unit = {
      toNet.expectComplete()
      requestIn.expectComplete()
    }
  }

  /** Provides the user handler flow as a handler function */
  trait HandlerFunctionSupport extends TestSetupWithoutHandshake {
    def parallelism: Int = 2
    def handler: HttpRequest ⇒ Future[HttpResponse] =
      _ ⇒ Future.successful(HttpResponse())

    def handlerFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)
  }

  def bytes(num: Int, byte: Byte): ByteString = ByteString(Array.fill[Byte](num)(byte))
}
