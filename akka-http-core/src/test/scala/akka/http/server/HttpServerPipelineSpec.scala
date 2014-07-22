/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http
package server

import akka.http.model.HttpEntity.{ LastChunk, Chunk, ChunkStreamPart }

import scala.concurrent.duration._

import akka.event.NoLogging
import akka.http.model.headers.Host
import akka.http.model._
import akka.http.util._
import akka.stream.io.StreamTcp
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import akka.util.ByteString
import org.scalatest._

import scala.concurrent.duration.FiniteDuration

class HttpServerPipelineSpec extends AkkaSpec with Matchers with BeforeAndAfterAll with Inside {
  val materializerSettings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  val materializer = FlowMaterializer(materializerSettings)

  "The server implementation should" should {
    "deliver an empty request as soon as all headers are received" in new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))
    }
    "deliver a request as soon as all headers are received" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNoMsg(50.millis)

          send("abcdef")
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMsg(50.millis)
      }
    }
    "deliver an error as soon as a parsing error occurred" in pendingUntilFixed(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      requests.expectError()
    })
    "report a invalid Chunked stream" in pendingUntilFixed(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("3ghi\r\n") // missing "\r\n" after the number of bytes
          dataProbe.expectError()
          requests.expectError()
      }
    })

    "deliver the request entity as it comes in strictly for an immediately completed Strict entity" in new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual
        HttpRequest(
          method = HttpMethods.POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))
    }
    "deliver the request entity as it comes in for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijk")
          dataProbe.expectNext(ByteString("ghijk"))
          dataProbe.expectNoMsg(50.millis)
      }
    }
    "deliver the request entity as it comes in for a chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(Chunk(ByteString("ghi")))
          dataProbe.expectNoMsg(50.millis)
      }
    }

    "deliver the second message properly after a Strict entity" in new TestSetup {
      send("""POST /strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdefghijkl""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual
        HttpRequest(
          method = HttpMethods.POST,
          uri = "http://example.com/strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("abcdefghijkl")))

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |mnopqrstuvwx""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual
        HttpRequest(
          method = HttpMethods.POST,
          uri = "http://example.com/next-strict",
          headers = List(Host("example.com")),
          entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("mnopqrstuvwx")))
    }
    "deliver the second message properly after a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghij")
          dataProbe.expectNext(ByteString("ghij"))

          send("kl")
          dataProbe.expectNext(ByteString("kl"))
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual (ByteString("abcde"))
      }
    }
    "deliver the second message properly after a Chunked entity" in new TestSetup {
      send("""POST /chunked HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))

          send("3\r\nghi\r\n")
          dataProbe.expectNext(ByteString("ghi"))
          dataProbe.expectNoMsg(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }

      send("""POST /next-strict HTTP/1.1
             |Host: example.com
             |Content-Length: 5
             |
             |abcde""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual (ByteString("abcde"))
      }
    }

    "close the request entity stream when the entity is complete for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))

          send("ghijkl")
          dataProbe.expectNext(ByteString("ghijkl"))
          dataProbe.expectComplete()
      }
    }
    "close the request entity stream when the entity is complete for a Chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }
    }

    "report a truncated entity stream on the entity data stream and the main stream for a Default entity" in pendingUntilFixed(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))
          dataProbe.expectNoMsg(50.millis)

          closeNetworkInput()
          dataProbe.expectError()
      }
    })
    "report a truncated entity stream on the entity data stream and the main stream for a Chunked entity" in pendingUntilFixed(new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.subscribe(dataProbe)
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          closeNetworkInput()
          dataProbe.expectError()
      }
    })
  }

  class TestSetup {
    val netIn = StreamTestKit.PublisherProbe[ByteString]
    val netOut = StreamTestKit.SubscriberProbe[ByteString]
    val tcpConnection = StreamTcp.IncomingTcpConnection(null, netIn, netOut)

    val pipeline = new HttpServerPipeline(ServerSettings(system), materializer, NoLogging)
    val Http.IncomingConnection(_, requestsIn, responsesOut) = pipeline(tcpConnection)

    val netInSub = netIn.expectSubscription()

    val requests = StreamTestKit.SubscriberProbe[HttpRequest]
    val responses = StreamTestKit.PublisherProbe[HttpResponse]
    requestsIn.subscribe(requests)
    val requestsSub = requests.expectSubscription()
    responses.subscribe(responsesOut)
    val responsesSub = responses.expectSubscription()

    def expectRequest: HttpRequest = {
      requestsSub.request(1)
      requests.expectNext()
    }
    def expectNoRequest(max: FiniteDuration): Unit = requests.expectNoMsg(max)

    def send(data: ByteString): Unit = netInSub.sendNext(data)
    def send(data: String): Unit = send(ByteString(data, "ASCII"))

    def closeNetworkInput(): Unit = netInSub.sendComplete()
  }
}
