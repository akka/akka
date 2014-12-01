/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.rendering

import com.typesafe.config.{ Config, ConfigFactory }
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ FreeSpec, Matchers, BeforeAndAfterAll }
import org.scalatest.matchers.Matcher
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._
import akka.stream.scaladsl._
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.FlowMaterializer
import akka.stream.impl.SynchronousIterablePublisher
import HttpEntity._
import HttpMethods._

class RequestRendererSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  implicit val materializer = FlowMaterializer()

  "The request preparation logic should" - {
    "properly render an unchunked" - {

      "GET request without headers and without body" in new TestSetup() {
        HttpRequest(GET, "/abc") should renderTo {
          """GET /abc HTTP/1.1
            |Host: test.com:8080
            |User-Agent: spray-can/1.0.0
            |
            |"""
        }
      }

      "GET request with a URI that requires encoding" in new TestSetup() {
        HttpRequest(GET, "/abc<def") should renderTo {
          """GET /abc%3Cdef HTTP/1.1
            |Host: test.com:8080
            |User-Agent: spray-can/1.0.0
            |
            |"""
        }
      }

      "POST request, a few headers (incl. a custom Host header) and no body" in new TestSetup() {
        HttpRequest(POST, "/abc/xyz", List(
          RawHeader("X-Fancy", "naa"),
          RawHeader("Age", "0"),
          Host("spray.io", 9999))) should renderTo {
          """POST /abc/xyz HTTP/1.1
            |X-Fancy: naa
            |Age: 0
            |Host: spray.io:9999
            |User-Agent: spray-can/1.0.0
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
            |User-Agent: spray-can/1.0.0
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
          Host("spray.io")), HttpEntity(ContentTypes.NoContentType, "The content please!")) should renderTo {
          """PUT /abc/xyz HTTP/1.1
            |X-Fancy: naa
            |Cache-Control: public
            |Host: spray.io
            |User-Agent: spray-can/1.0.0
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
              |User-Agent: spray-can/1.0.0
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 19
              |
              |The content please!"""
          }
      }
    }

    "proper render a chunked" - {

      "PUT request with empty chunk stream and custom Content-Type" in new TestSetup() {
        pending // Disabled until #15981 is fixed
        HttpRequest(PUT, "/abc/xyz", entity = Chunked(ContentTypes.`text/plain`, source())) should renderTo {
          """PUT /abc/xyz HTTP/1.1
            |Host: test.com:8080
            |User-Agent: spray-can/1.0.0
            |Content-Type: text/plain
            |Content-Length: 0
            |
            |"""
        }
      }

      "POST request with body" in new TestSetup() {
        HttpRequest(POST, "/abc/xyz", entity = Chunked(ContentTypes.`text/plain`,
          source("XXXX", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))) should renderTo {
          """POST /abc/xyz HTTP/1.1
              |Host: test.com:8080
              |User-Agent: spray-can/1.0.0
              |Transfer-Encoding: chunked
              |Content-Type: text/plain
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
          entity = Chunked(ContentTypes.`text/plain`, source("XXXX", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"))) should renderTo {
            """POST /abc/xyz HTTP/1.1
              |Transfer-Encoding: fancy, chunked
              |Host: test.com:8080
              |User-Agent: spray-can/1.0.0
              |Content-Type: text/plain
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
      "if suppressRendering = false" in new TestSetup(None) {
        case class MyHeader(number: Int) extends CustomHeader {
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
      "not if suppressRendering = true" in new TestSetup(None) {
        case class MyInternalHeader(number: Int) extends CustomHeader {
          override def suppressRendering: Boolean = true

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
            |User-Agent: spray-can/1.0.0
            |
            |"""
        }
      }

      "GET request with Raw-Request-URI sends raw URI even with invalid utf8 characters" in new TestSetup() {
        HttpRequest(GET, "/abc", List(`Raw-Request-URI`("/def%80%fe%ff"))) should renderTo {
          """GET /def%80%fe%ff HTTP/1.1
            |Host: test.com:8080
            |User-Agent: spray-can/1.0.0
            |
            |"""
        }
      }
    }
  }

  override def afterAll() = system.shutdown()

  class TestSetup(val userAgent: Option[`User-Agent`] = Some(`User-Agent`("spray-can/1.0.0")),
                  serverAddress: InetSocketAddress = new InetSocketAddress("test.com", 8080))
    extends HttpRequestRendererFactory(userAgent, requestHeaderSizeHint = 64, NoLogging) {

    def renderTo(expected: String): Matcher[HttpRequest] =
      equal(expected.stripMarginWithNewline("\r\n")).matcher[String] compose { request ⇒
        val renderer = newRenderer
        val byteStringSource = Await.result(Source.singleton(RequestRenderingContext(request, serverAddress)).
          section(name("renderer"))(_.transform(() ⇒ renderer)).
          runWith(Sink.head), 1.second)
        val future = byteStringSource.grouped(1000).runWith(Sink.head).map(_.reduceLeft(_ ++ _).utf8String)
        Await.result(future, 250.millis)
      }
  }

  def source[T](elems: T*) = Source(elems.toList)
}
