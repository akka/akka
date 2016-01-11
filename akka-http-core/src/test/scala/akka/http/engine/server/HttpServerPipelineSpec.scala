/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.server

import scala.concurrent.duration._
import org.scalatest.{ Inside, BeforeAndAfterAll, Matchers }
import akka.event.NoLogging
import akka.util.ByteString
import akka.stream.scaladsl._
import akka.stream.FlowMaterializer
import akka.stream.io.StreamTcp
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.http.Http
import akka.http.model._
import akka.http.util._
import headers._
import HttpEntity._
import MediaTypes._
import HttpMethods._

class HttpServerPipelineSpec extends AkkaSpec with Matchers with BeforeAndAfterAll with Inside {
  implicit val materializer = FlowMaterializer()

  "The server implementation" should {

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
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
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

    "deliver an error response as soon as a parsing error occurred" in new TestSetup {
      send("""GET / HTTP/1.2
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      netOutSub.request(1)
      wipeDate(netOut.expectNext().utf8String) shouldEqual
        """HTTP/1.1 505 HTTP Version Not Supported
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Type: text/plain; charset=UTF-8
          |Content-Length: 74
          |
          |The server does not support the HTTP protocol version used in the request.""".stripMarginWithNewline("\r\n")
    }

    "report an invalid Chunked stream" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("3ghi\r\n") // missing "\r\n" after the number of bytes
          val error = dataProbe.expectError()
          error.getMessage shouldEqual "Illegal character 'g' in chunk start"
          requests.expectComplete()

          netOutSub.request(1)
          responsesSub.expectRequest()
          responsesSub.sendError(error.asInstanceOf[Exception])

          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 400 Bad Request
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 36
              |
              |Illegal character 'g' in chunk start""".stripMarginWithNewline("\r\n")
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
          method = POST,
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
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
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
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
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
          method = POST,
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
          method = POST,
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
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
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
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
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
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
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
        case HttpRequest(POST, _, _, HttpEntity.Strict(_, data), _) ⇒
          data shouldEqual ByteString("abcde")
      }
    }

    "close the request entity stream when the entity is complete for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))

      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
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
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)

          send("0\r\n\r\n")
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
      }
    }

    "report a truncated entity stream on the entity data stream and the main stream for a Default entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Content-Length: 12
             |
             |abcdef""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Default(_, 12, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(ByteString("abcdef"))
          dataProbe.expectNoMsg(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
    }

    "report a truncated entity stream on the entity data stream and the main stream for a Chunked entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Transfer-Encoding: chunked
             |
             |6
             |abcdef
             |""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(POST, _, _, HttpEntity.Chunked(_, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val sub = dataProbe.expectSubscription()
          sub.request(10)
          dataProbe.expectNext(Chunk(ByteString("abcdef")))
          dataProbe.expectNoMsg(50.millis)
          closeNetworkInput()
          dataProbe.expectError().getMessage shouldEqual "Entity stream truncation"
      }
    }

    "translate HEAD request to GET request when transparent-head-requests are enabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = true)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      expectRequest shouldEqual HttpRequest(GET, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "keep HEAD request when transparent-head-requests are disabled" in new TestSetup {
      override def settings = ServerSettings(system).copy(transparentHeadRequests = false)
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      expectRequest shouldEqual HttpRequest(HEAD, uri = "http://example.com/", headers = List(Host("example.com")))
    }

    "not emit entities when responding to HEAD requests if transparent-head-requests is enabled (with Strict)" in new TestSetup {
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
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
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      val data = StreamTestKit.PublisherProbe[ByteString]
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
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
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      val data = StreamTestKit.PublisherProbe[ByteString]
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
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
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))
      val data = StreamTestKit.PublisherProbe[ChunkStreamPart]
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
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
      send("""HEAD / HTTP/1.1
             |Host: example.com
             |Connection: close
             |
             |""".stripMarginWithNewline("\r\n"))
      val data = StreamTestKit.PublisherProbe[ByteString]
      inside(expectRequest) {
        case HttpRequest(GET, _, _, _, _) ⇒
          responsesSub.sendNext(HttpResponse(entity = CloseDelimited(ContentTypes.`text/plain`, Source(data))))
          netOutSub.request(1)
          val dataSub = data.expectSubscription()
          dataSub.expectCancellation()
          netOut.expectNext()
      }
      netOut.expectComplete()
    }

    "produce a `100 Continue` response when requested by a `Default` entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ByteString]
          data.to(Sink(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOutSub.request(2)
          netOut.expectNoMsg(50.millis)
          dataSub.request(1) // triggers `100 Continue` response
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |Content-Length: 0
              |
              |""".stripMarginWithNewline("\r\n")
          dataProbe.expectNoMsg(50.millis)
          send("0123456789ABCDEF")
          dataProbe.expectNext(ByteString("0123456789ABCDEF"))
          dataProbe.expectComplete()
          responsesSub.sendNext(HttpResponse(entity = "Yeah"))
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""".stripMarginWithNewline("\r\n")
      }
    }

    "produce a `100 Continue` response when requested by a `Chunked` entity" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Transfer-Encoding: chunked
             |
             |""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Chunked(ContentType(`application/octet-stream`, None), data), _) ⇒
          val dataProbe = StreamTestKit.SubscriberProbe[ChunkStreamPart]
          data.to(Sink(dataProbe)).run()
          val dataSub = dataProbe.expectSubscription()
          netOutSub.request(2)
          netOut.expectNoMsg(50.millis)
          dataSub.request(2) // triggers `100 Continue` response
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 100 Continue
              |Server: akka-http/test
              |Date: XXXX
              |Content-Length: 0
              |
              |""".stripMarginWithNewline("\r\n")
          dataProbe.expectNoMsg(50.millis)
          send("""10
                 |0123456789ABCDEF
                 |0
                 |
                 |""".stripMarginWithNewline("\r\n"))
          dataProbe.expectNext(Chunk(ByteString("0123456789ABCDEF")))
          dataProbe.expectNext(LastChunk)
          dataProbe.expectComplete()
          responsesSub.sendNext(HttpResponse(entity = "Yeah"))
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""".stripMarginWithNewline("\r\n")
      }
    }

    "render a closing response instead of `100 Continue` if request entity is not requested" in new TestSetup {
      send("""POST / HTTP/1.1
             |Host: example.com
             |Expect: 100-continue
             |Content-Length: 16
             |
             |""".stripMarginWithNewline("\r\n"))
      inside(expectRequest) {
        case HttpRequest(POST, _, _, Default(ContentType(`application/octet-stream`, None), 16, data), _) ⇒
          netOutSub.request(1)
          responsesSub.sendNext(HttpResponse(entity = "Yeah"))
          wipeDate(netOut.expectNext().utf8String) shouldEqual
            """HTTP/1.1 200 OK
              |Server: akka-http/test
              |Date: XXXX
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 4
              |
              |Yeah""".stripMarginWithNewline("\r\n")
      }
    }

    "render a 500 response on response stream errors from the application" in new TestSetup {
      send("""GET / HTTP/1.1
             |Host: example.com
             |
             |""".stripMarginWithNewline("\r\n"))

      expectRequest shouldEqual HttpRequest(uri = "http://example.com/", headers = List(Host("example.com")))

      netOutSub.request(1)
      responsesSub.expectRequest()
      responsesSub.sendError(new RuntimeException("CRASH BOOM BANG"))

      wipeDate(netOut.expectNext().utf8String) shouldEqual
        """HTTP/1.1 500 Internal Server Error
          |Server: akka-http/test
          |Date: XXXX
          |Connection: close
          |Content-Length: 0
          |
          |""".stripMarginWithNewline("\r\n")
    }
  }

  class TestSetup {
    val netIn = StreamTestKit.PublisherProbe[ByteString]
    val netOut = StreamTestKit.SubscriberProbe[ByteString]
    val tcpConnection = StreamTcp.IncomingTcpConnection(null, netIn, netOut)

    def settings = ServerSettings(system).copy(serverHeader = Some(Server(List(ProductVersion("akka-http", "test")))))

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
