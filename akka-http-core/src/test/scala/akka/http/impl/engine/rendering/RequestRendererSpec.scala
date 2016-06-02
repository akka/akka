/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import com.typesafe.config.{ Config, ConfigFactory }
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ FreeSpec, Matchers, BeforeAndAfterAll }
import org.scalatest.matchers.Matcher
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.util.ByteString
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import HttpEntity._
import HttpMethods._

class RequestRendererSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  implicit val materializer = ActorMaterializer()

  "The request preparation logic should" - {
    "properly render an unchunked" - {

      "GET request without headers and without body" in new TestSetup() {
        HttpRequest(GET, "/abc") should renderTo {
          """GET /abc HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }

      "GET request with a URI that requires encoding" in new TestSetup() {
        HttpRequest(GET, "/abc<def") should renderTo {
          """GET /abc%3Cdef HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }

      "GET request to a literal IPv6 address" in new TestSetup(serverAddress = new InetSocketAddress("[::1]", 8080)) {
        HttpRequest(GET, uri = "/abc") should renderTo {
          """GET /abc HTTP/1.1
            |Host: [0:0:0:0:0:0:0:1]:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }

      "POST request, a few headers (incl. a custom Host header) and no body" in new TestSetup() {
        HttpRequest(POST, "/abc/xyz", List(
          RawHeader("X-Fancy", "naa"),
          Link(Uri("http://akka.io"), LinkParams.first),
          Host("spray.io", 9999))) should renderTo {
          """POST /abc/xyz HTTP/1.1
            |X-Fancy: naa
            |Link: <http://akka.io>; rel=first
            |Host: spray.io:9999
            |User-Agent: akka-http/1.0.0
            |Content-Length: 0
            |
            |"""
        }
      }

      "PUT request, a few headers and a body" in new TestSetup() {
        HttpRequest(PUT, "/abc/xyz", List(
          RawHeader("X-Fancy", "naa"),
          RawHeader("Cache-Control", "public"),
          Host("spray.io"))).withEntity("The content please!") should renderTo {
          """PUT /abc/xyz HTTP/1.1
            |X-Fancy: naa
            |Cache-Control: public
            |Host: spray.io
            |User-Agent: akka-http/1.0.0
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 19
            |
            |The content please!"""
        }
      }

      "PUT request, a few headers and a body with suppressed content type" in new TestSetup() {
        HttpRequest(PUT, "/abc/xyz", List(
          RawHeader("X-Fancy", "naa"),
          RawHeader("Cache-Control", "public"),
          Host("spray.io")), HttpEntity(ContentTypes.NoContentType, ByteString("The content please!"))) should renderTo {
          """PUT /abc/xyz HTTP/1.1
            |X-Fancy: naa
            |Cache-Control: public
            |Host: spray.io
            |User-Agent: akka-http/1.0.0
            |Content-Length: 19
            |
            |The content please!"""
        }
      }

      "PUT request with a custom Transfer-Encoding header" in new TestSetup() {
        HttpRequest(PUT, "/abc/xyz", List(`Transfer-Encoding`(TransferEncodings.Extension("fancy"))))
          .withEntity("The content please!") should renderTo {
            """PUT /abc/xyz HTTP/1.1
              |Transfer-Encoding: fancy
              |Host: test.com:8080
              |User-Agent: akka-http/1.0.0
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 19
              |
              |The content please!"""
          }
      }

      "DELETE request without headers and without body" in new TestSetup() {
        HttpRequest(DELETE, "/abc") should renderTo {
          """DELETE /abc HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }
    }

    "proper render a chunked" - {

      "PUT request with empty chunk stream and custom Content-Type" in new TestSetup() {
        HttpRequest(PUT, "/abc/xyz", entity = Chunked(ContentTypes.`text/plain(UTF-8)`, Source.empty)) should renderTo {
          """PUT /abc/xyz HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 0
            |
            |"""
        }
      }

      "POST request with body" in new TestSetup() {
        HttpRequest(POST, "/abc/xyz", entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          source("XXXX", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))) should renderTo {
          """POST /abc/xyz HTTP/1.1
              |Host: test.com:8080
              |User-Agent: akka-http/1.0.0
              |Transfer-Encoding: chunked
              |Content-Type: text/plain; charset=UTF-8
              |
              |4
              |XXXX
              |1a
              |ABCDEFGHIJKLMNOPQRSTUVWXYZ
              |0
              |
              |"""
        }
      }

      "POST request with chunked body and explicit LastChunk" in new TestSetup() {
        val chunks =
          List(
            ChunkStreamPart("XXXX"),
            ChunkStreamPart("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            LastChunk)

        HttpRequest(POST, "/abc/xyz", entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          Source(chunks))) should renderTo {
          """POST /abc/xyz HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |4
            |XXXX
            |1a
            |ABCDEFGHIJKLMNOPQRSTUVWXYZ
            |0
            |
            |"""
        }
      }

      "POST request with chunked body and extra LastChunks at the end (which should be ignored)" in new TestSetup() {
        val chunks =
          List(
            ChunkStreamPart("XXXX"),
            ChunkStreamPart("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            LastChunk,
            LastChunk)

        HttpRequest(POST, "/abc/xyz", entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          Source(chunks))) should renderTo {
          """POST /abc/xyz HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |4
            |XXXX
            |1a
            |ABCDEFGHIJKLMNOPQRSTUVWXYZ
            |0
            |
            |"""
        }
      }

      "POST request with custom Transfer-Encoding header" in new TestSetup() {
        HttpRequest(POST, "/abc/xyz", List(`Transfer-Encoding`(TransferEncodings.Extension("fancy"))),
          entity = Chunked(ContentTypes.`text/plain(UTF-8)`, source("XXXX", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))) should renderTo {
            """POST /abc/xyz HTTP/1.1
              |Transfer-Encoding: fancy, chunked
              |Host: test.com:8080
              |User-Agent: akka-http/1.0.0
              |Content-Type: text/plain; charset=UTF-8
              |
              |4
              |XXXX
              |1a
              |ABCDEFGHIJKLMNOPQRSTUVWXYZ
              |0
              |
              |"""
          }
      }
    }

    "properly handle the User-Agent header" - {
      "if no default is set and no explicit User-Agent header given" in new TestSetup(None) {
        HttpRequest(GET, "/abc") should renderTo {
          """GET /abc HTTP/1.1
            |Host: test.com:8080
            |
            |"""
        }
      }
      "if a default is set but an explicit User-Agent header given" in new TestSetup() {
        HttpRequest(GET, "/abc", List(`User-Agent`("user-ua/1.0"))) should renderTo {
          """GET /abc HTTP/1.1
            |User-Agent: user-ua/1.0
            |Host: test.com:8080
            |
            |"""
        }
      }
    }
    "render a CustomHeader header" - {
      "if renderInRequests = true" in new TestSetup(None) {
        case class MyHeader(number: Int) extends CustomHeader {
          def renderInRequests = true
          def renderInResponses = false
          def name: String = "X-My-Header"
          def value: String = s"No$number"
        }
        HttpRequest(GET, "/abc", List(MyHeader(5))) should renderTo {
          """GET /abc HTTP/1.1
            |X-My-Header: No5
            |Host: test.com:8080
            |
            |"""
        }
      }
      "not if renderInRequests = false" in new TestSetup(None) {
        case class MyInternalHeader(number: Int) extends CustomHeader {
          def renderInRequests = false
          def renderInResponses = false
          def name: String = "X-My-Internal-Header"
          def value: String = s"No$number"
        }
        HttpRequest(GET, "/abc", List(MyInternalHeader(5))) should renderTo {
          """GET /abc HTTP/1.1
            |Host: test.com:8080
            |
            |"""
        }
      }
    }

    "properly use URI from Raw-Request-URI header if present" - {
      "GET request with Raw-Request-URI" in new TestSetup() {
        HttpRequest(GET, "/abc", List(`Raw-Request-URI`("/def"))) should renderTo {
          """GET /def HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }

      "GET request with Raw-Request-URI sends raw URI even with invalid utf8 characters" in new TestSetup() {
        HttpRequest(GET, "/abc", List(`Raw-Request-URI`("/def%80%fe%ff"))) should renderTo {
          """GET /def%80%fe%ff HTTP/1.1
            |Host: test.com:8080
            |User-Agent: akka-http/1.0.0
            |
            |"""
        }
      }
    }
  }

  override def afterAll() = system.terminate()

  class TestSetup(
    val userAgent: Option[`User-Agent`] = Some(`User-Agent`("akka-http/1.0.0")),
    serverAddress: InetSocketAddress    = new InetSocketAddress("test.com", 8080))
    extends HttpRequestRendererFactory(userAgent, requestHeaderSizeHint = 64, NoLogging) {

    def awaitAtMost: FiniteDuration = 3.seconds

    def renderTo(expected: String): Matcher[HttpRequest] =
      equal(expected.stripMarginWithNewline("\r\n")).matcher[String] compose { request ⇒
        val byteStringSource = renderToSource(RequestRenderingContext(request, Host(serverAddress)))
        val future = byteStringSource.limit(1000).runWith(Sink.seq).map(_.reduceLeft(_ ++ _).utf8String)
        Await.result(future, awaitAtMost)
      }
  }

  def source[T](elems: T*) = Source(elems.toList)
}
