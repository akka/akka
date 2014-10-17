/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import scala.concurrent.duration._
import akka.event.NoLogging
import akka.http.model.HttpEntity.{ Chunk, ChunkStreamPart, LastChunk }
import akka.http.model._
import akka.http.model.headers.{ ProductVersion, Server, Host }
import akka.http.util._
import akka.http.Http
import akka.stream.scaladsl2._
import akka.stream.io.StreamTcp
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.util.ByteString
import org.scalatest._

class HttpServerPipelineSpec extends AkkaSpec with Matchers with BeforeAndAfterAll with Inside {
  implicit val materializer = FlowMaterializer()

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
          data.connect(Sink(dataProbe)).run()
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
    "deliver an error as soon as a parsing error occurred" in new TestSetup {
      pending
      // POST should require Content-Length header
      send("""POST / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      requests.expectError()
    }
    "report a invalid Chunked stream" in new TestSetup {
      pending
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
          data.connect(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("3ghi\r\n") // missing "\r\n" after the number of bytes
          dataProbe.expectError()
          requests.expectError()
      }
    }

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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
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
          data.connect(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          closeNetworkInput()
          dataProbe.expectError()
      }
    })

    "translate HEAD request to GET request when transparent-head-requests are enabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = true)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual HttpRequest(HttpMethods.GET, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "keep HEAD request when transparent-head-requests are disabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = false)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual HttpRequest(HttpMethods.HEAD, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Strict)" in new TestSetup {
      override def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(HttpMethods.GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = HttpEntity.Strict(ContentTypes.`text/plain`, ByteString("abcd"))))

          netOutSub.request(1)
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |Content-Length: 4
               |
               |""".stripMarginWithNewline("\r\n")
      }
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Default)" in new TestSetup {
      override def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      val data = StreamTestKit.PublisherProbe[ByteString]

      inside(expectRequest) {
        case HttpRequest(HttpMethods.GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = HttpEntity.Default(ContentTypes.`text/plain`, 4, Source(data))))

          netOutSub.request(1)

          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()

          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |Content-Length: 4
               |
               |""".stripMarginWithNewline("\r\n")
      }
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with CloseDelimited)" in new TestSetup {
      override def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      val data = StreamTestKit.PublisherProbe[ByteString]

      inside(expectRequest) {
        case HttpRequest(HttpMethods.GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`text/plain`, Source(data))))

          netOutSub.request(1)

          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()

          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Content-Type: text/plain
               |
               |""".stripMarginWithNewline("\r\n")
      }

      // No close should happen here since this was a HEAD request
      netOut.expectNoMsg(50.millis)
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Chunked)" in new TestSetup {
      override def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      val data = StreamTestKit.PublisherProbe[ChunkStreamPart]

      inside(expectRequest) {
        case HttpRequest(HttpMethods.GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = HttpEntity.Chunked(ContentTypes.`text/plain`, Source(data))))

          netOutSub.request(1)

          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()

          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """|HTTP/1.1 200 OK
               |Server: akka-http/test
               |Date: XXXX
               |Transfer-Encoding: chunked
               |Content-Type: text/plain
               |
               |""".stripMarginWithNewline("\r\n")
      }
    }

    "respect Connection headers of HEAD requests if transparent-head-requests is enabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |Connection: close
             |
             |""".stripMarginWithNewline("\r\n"))

      val data = StreamTestKit.PublisherProbe[ByteString]

      inside(expectRequest) {
        case HttpRequest(HttpMethods.GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = HttpEntity.CloseDelimited(ContentTypes.`text/plain`, Source(data))))

          netOutSub.request(1)

          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()

          netOut.expectNext()
      }

      netOut.expectComplete()
    }
  }

  class TestSetup {
    val netIn = StreamTestKit.PublisherProbe[ByteString]
    val netOut = StreamTestKit.SubscriberProbe[ByteString]
    val tcpConnection = StreamTcp.IncomingTcpConnection(null, netIn, netOut)

    def settings = ServerSettings(system)

    val pipeline = new HttpServerPipeline(settings, NoLogging)
    val Http.IncomingConnection(_, requestsIn, responsesOut) = pipeline(tcpConnection)

    def wipeDate(string: String) =
      string.fastSplit('\n').map {
        case s if s.startsWith("Date:") ⇒ "Date: XXXX\r"
        case s                          ⇒ s
      }.mkString("\n")

    val netInSub = netIn.expectSubscription()
    val netOutSub = netOut.expectSubscription()

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
