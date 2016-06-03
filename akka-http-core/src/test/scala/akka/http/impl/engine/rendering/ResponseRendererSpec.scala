/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.rendering

import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ FreeSpec, Matchers, BeforeAndAfterAll }
import org.scalatest.matchers.Matcher
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._
import akka.util.ByteString
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import HttpEntity._

import scala.util.control.NonFatal

class ResponseRendererSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  val ServerOnTheMove = StatusCodes.custom(330, "Server on the move")
  implicit val materializer = ActorMaterializer()

  "The response preparation logic should properly render" - {
    "a response with no body," - {
      "status 200 and no headers" in new TestSetup() {
        HttpResponse(200) should renderTo {
          """HTTP/1.1 200 OK
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }

      "a custom Date header" in new TestSetup() {
        HttpResponse(200, List(Date(DateTime(2011, 8, 26, 10, 11, 59)))) should renderTo {
          """HTTP/1.1 200 OK
            |Date: Fri, 26 Aug 2011 10:11:59 GMT
            |Server: akka-http/1.0.0
            |Content-Length: 0
            |
            |"""
        }
      }

      "status 304 and a few headers" in new TestSetup() {
        HttpResponse(304, List(RawHeader("X-Fancy", "of course"), Age(0))) should renderTo {
          """HTTP/1.1 304 Not Modified
            |X-Fancy: of course
            |Age: 0
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |
            |"""
        }
      }
      "a custom status code and no headers" in new TestSetup() {
        HttpResponse(ServerOnTheMove) should renderTo {
          """HTTP/1.1 330 Server on the move
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }
      "a custom status code and no headers and different dates" in new TestSetup() {
        val initial = DateTime(2011, 8, 25, 9, 10, 0).clicks
        var extraMillis = 0L
        (0 until 10000 by 500) foreach { millis ⇒
          extraMillis = millis
          HttpResponse(200) should renderTo {
            s"""HTTP/1.1 200 OK
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:0${extraMillis / 1000 % 60} GMT
              |Content-Length: 0
              |
              |"""
          }
        }

        override def currentTimeMillis() = initial + extraMillis
      }

      "to a transparent HEAD request (Strict response entity)" in new TestSetup() {
        ResponseRenderingContext(
          requestMethod = HttpMethods.HEAD,
          response = HttpResponse(
            headers = List(Age(30), Connection("Keep-Alive")),
            entity = "Small f*ck up overhere!")) should renderTo(
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 23
              |
              |""", close = false)
      }

      "to a transparent HEAD request (CloseDelimited response entity)" in new TestSetup() {
        ResponseRenderingContext(
          requestMethod = HttpMethods.HEAD,
          response = HttpResponse(
            headers = List(Age(30), Connection("Keep-Alive")),
            entity = HttpEntity.CloseDelimited(
              ContentTypes.`text/plain(UTF-8)`,
              Source.single(ByteString("Foo"))))) should renderTo(
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |
              |""", close = false)
      }

      "to a transparent HEAD request (Chunked response entity)" in new TestSetup() {
        ResponseRenderingContext(
          requestMethod = HttpMethods.HEAD,
          response = HttpResponse(
            headers = List(Age(30), Connection("Keep-Alive")),
            entity = HttpEntity.Chunked(
              ContentTypes.`text/plain(UTF-8)`,
              Source.single(HttpEntity.Chunk(ByteString("Foo")))))) should renderTo(
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Transfer-Encoding: chunked
              |Content-Type: text/plain; charset=UTF-8
              |
              |""", close = false)
      }

      "to a HEAD request setting a custom Content-Type and Content-Length (default response entity)" in new TestSetup() {
        ResponseRenderingContext(
          requestMethod = HttpMethods.HEAD,
          response = HttpResponse(
            headers = List(Age(30)),
            entity = HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, 100, Source.empty))) should renderTo(
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 100
              |
              |""", close = false)
      }
    }

    "a response with a Strict body," - {
      "status 400 and a few headers" in new TestSetup() {
        HttpResponse(400, List(Age(30), Connection("Keep-Alive")), "Small f*ck up overhere!") should renderTo {
          """HTTP/1.1 400 Bad Request
            |Age: 30
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 23
            |
            |Small f*ck up overhere!"""
        }
      }

      "status 400, a few headers and a body with an explicitly suppressed Content Type header" in new TestSetup() {
        HttpResponse(400, List(Age(30), Connection("Keep-Alive")),
          HttpEntity(ContentTypes.NoContentType, ByteString("Small f*ck up overhere!"))) should renderTo {
            """HTTP/1.1 400 Bad Request
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Length: 23
              |
              |Small f*ck up overhere!"""
          }
      }

      "status 200 and a custom Transfer-Encoding header" in new TestSetup() {
        HttpResponse(
          headers = List(`Transfer-Encoding`(TransferEncodings.Extension("fancy"))),
          entity = "All good") should renderTo {
            """HTTP/1.1 200 OK
              |Transfer-Encoding: fancy
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 8
              |
              |All good"""
          }
      }
    }
    "a response with a Default (streamed with explicit content-length body," - {
      "status 400 and a few headers" in new TestSetup() {
        HttpResponse(400, List(Age(30), Connection("Keep-Alive")),
          entity = Default(ContentTypes.`text/plain(UTF-8)`, 23, source(ByteString("Small f*ck up overhere!")))) should renderTo {
            """HTTP/1.1 400 Bad Request
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |Content-Length: 23
              |
              |Small f*ck up overhere!"""
          }
      }
      "one chunk and incorrect (too large) Content-Length" in new TestSetup() {
        the[RuntimeException] thrownBy {
          HttpResponse(200, entity = Default(ContentTypes.`application/json`, 10,
            source(ByteString("body123")))) should renderTo("")
        } should have message "HTTP message had declared Content-Length 10 but entity data stream amounts to 3 bytes less"
      }

      "one chunk and incorrect (too small) Content-Length" in new TestSetup() {
        the[RuntimeException] thrownBy {
          HttpResponse(200, entity = Default(ContentTypes.`application/json`, 5,
            source(ByteString("body123")))) should renderTo("")
        } should have message "HTTP message had declared Content-Length 5 but entity data stream amounts to more bytes"
      }

    }
    "a response with a CloseDelimited body" - {
      "without data" in new TestSetup() {
        ResponseRenderingContext(
          HttpResponse(200, entity = CloseDelimited(
            ContentTypes.`application/json`,
            source(ByteString.empty)))) should renderTo(
            """HTTP/1.1 200 OK
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Connection: close
              |Content-Type: application/json
              |
              |""", close = true)
      }
      "consisting of two parts" in new TestSetup() {
        ResponseRenderingContext(
          HttpResponse(200, entity = CloseDelimited(
            ContentTypes.`application/json`,
            source(ByteString("abc"), ByteString("defg"))))) should renderTo(
            """HTTP/1.1 200 OK
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Connection: close
              |Content-Type: application/json
              |
              |abcdefg""", close = true)
      }
    }

    "a chunked response" - {
      "with empty entity" in new TestSetup() {
        HttpResponse(200, List(Age(30)),
          Chunked(ContentTypes.NoContentType, Source.empty)) should renderTo {
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |
              |"""
          }
      }

      "with empty entity but non-default Content-Type" in new TestSetup() {
        HttpResponse(200, List(Age(30)),
          Chunked(ContentTypes.`application/json`, Source.empty)) should renderTo {
            """HTTP/1.1 200 OK
              |Age: 30
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: application/json
              |
              |"""
          }
      }

      "with one chunk and no explicit LastChunk" in new TestSetup() {
        HttpResponse(entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          source("Yahoooo"))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |7
            |Yahoooo
            |0
            |
            |"""
        }
      }

      "with one chunk and an explicit LastChunk" in new TestSetup() {
        HttpResponse(entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          source(
            Chunk(ByteString("body123"), """key=value;another="tl;dr""""),
            LastChunk("foo=bar", List(Age(30), RawHeader("Cache-Control", "public")))))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |7;key=value;another="tl;dr"
            |body123
            |0;foo=bar
            |Age: 30
            |Cache-Control: public
            |
            |"""
        }
      }

      "with one chunk and and extra LastChunks at the end (which should be ignored)" in new TestSetup() {
        HttpResponse(entity = Chunked(
          ContentTypes.`text/plain(UTF-8)`,
          source(
            Chunk(ByteString("body123"), """key=value;another="tl;dr""""),
            LastChunk("foo=bar", List(Age(30), RawHeader("Cache-Control", "public"))), LastChunk))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: akka-http/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Transfer-Encoding: chunked
            |Content-Type: text/plain; charset=UTF-8
            |
            |7;key=value;another="tl;dr"
            |body123
            |0;foo=bar
            |Age: 30
            |Cache-Control: public
            |
            |"""
        }
      }

      "with a custom Transfer-Encoding header" in new TestSetup() {
        HttpResponse(
          headers = List(`Transfer-Encoding`(TransferEncodings.Extension("fancy"))),
          entity = Chunked(ContentTypes.`text/plain(UTF-8)`, source("Yahoooo"))) should renderTo {
            """HTTP/1.1 200 OK
              |Transfer-Encoding: fancy, chunked
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Content-Type: text/plain; charset=UTF-8
              |
              |7
              |Yahoooo
              |0
              |
              |"""
          }
      }
    }

    "chunked responses to a HTTP/1.0 request" - {
      "with two chunks" in new TestSetup() {
        ResponseRenderingContext(
          requestProtocol = HttpProtocols.`HTTP/1.0`,
          response = HttpResponse(entity = Chunked(
            ContentTypes.`application/json`,
            source(Chunk("abc"), Chunk("defg"))))) should renderTo(
            """HTTP/1.1 200 OK
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Connection: close
              |Content-Type: application/json
              |
              |abcdefg""", close = true)
      }

      "with one chunk and an explicit LastChunk" in new TestSetup() {
        ResponseRenderingContext(
          requestProtocol = HttpProtocols.`HTTP/1.0`,
          response = HttpResponse(entity = Chunked(
            ContentTypes.`text/plain(UTF-8)`,
            source(
              Chunk(ByteString("body123"), """key=value;another="tl;dr""""),
              LastChunk("foo=bar", List(Age(30), RawHeader("Cache-Control", "public"))))))) should renderTo(
            """HTTP/1.1 200 OK
              |Server: akka-http/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Connection: close
              |Content-Type: text/plain; charset=UTF-8
              |
              |body123""", close = true)
      }
    }

    "properly handle the Server header" - {
      "if no default is set and no explicit Server header given" in new TestSetup(None) {
        HttpResponse(200) should renderTo {
          """HTTP/1.1 200 OK
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }
      "if a default is set but an explicit Server header given" in new TestSetup() {
        HttpResponse(200, List(Server("server/1.0"))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: server/1.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }
    }

    "render a CustomHeader header" - {
      "if renderInResponses = true" in new TestSetup(None) {
        case class MyHeader(number: Int) extends CustomHeader {
          def renderInRequests = false
          def renderInResponses = true
          def name: String = "X-My-Header"
          def value: String = s"No$number"
        }
        HttpResponse(200, List(MyHeader(5))) should renderTo {
          """HTTP/1.1 200 OK
            |X-My-Header: No5
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }
      "not if renderInResponses = false" in new TestSetup(None) {
        case class MyInternalHeader(number: Int) extends CustomHeader {
          def renderInRequests = false
          def renderInResponses = false
          def name: String = "X-My-Internal-Header"
          def value: String = s"No$number"
        }
        HttpResponse(200, List(MyInternalHeader(5))) should renderTo {
          """HTTP/1.1 200 OK
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }
    }

    "The 'Connection' header should be rendered correctly" in new TestSetup() {
      import org.scalatest.prop.TableDrivenPropertyChecks._
      import HttpProtocols._

      def NONE: Option[Connection] = None
      def CLOSE: Option[Connection] = Some(Connection("close"))
      def KEEPA: Option[Connection] = Some(Connection("Keep-Alive"))
      // format: OFF
      val table = Table(
       //--- requested by the client ----// //----- set by server app -----// //--- actually done ---//
       //            Request    Request               Response    Response    Rendered     Connection
       // Request    Method     Connection  Response  Connection  Entity      Connection   Closed after
       // Protocol   is HEAD    Header      Protocol  Header      CloseDelim  Header       Response
        ("Req Prot", "HEAD Req", "Req CH", "Res Prot", "Res CH", "Res CD",    "Ren Conn",  "Close"),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  NONE,    false,       NONE,        false),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  KEEPA,   false,       NONE,        false),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.1`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, false,      NONE,     `HTTP/1.0`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.1`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, false,      CLOSE,    `HTTP/1.0`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  NONE,    false,       NONE,        false),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  NONE,    true,        NONE,        false),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  KEEPA,   false,       NONE,        false),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.1`,  KEEPA,   true,        NONE,        false),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, true,       NONE,     `HTTP/1.0`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.1`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.1`, true,       CLOSE,    `HTTP/1.0`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.1`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  NONE,    false,       NONE,        true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  NONE,    true,        NONE,        true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  CLOSE,   false,       NONE,        true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  CLOSE,   true,        NONE,        true),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, false,      NONE,     `HTTP/1.0`,  KEEPA,   true,        NONE,        true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  NONE,    false,       KEEPA,       false),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.1`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  NONE,    false,       KEEPA,       false),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, false,      KEEPA,    `HTTP/1.0`,  KEEPA,   true,        CLOSE,       true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  NONE,    false,       CLOSE,       true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  NONE,    true,        CLOSE,       true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.1`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  NONE,    false,       NONE,        true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  NONE,    true,        NONE,        true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  CLOSE,   false,       NONE,        true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  CLOSE,   true,        NONE,        true),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, true,       NONE,     `HTTP/1.0`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  NONE,    false,       KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  NONE,    true,        KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.1`,  KEEPA,   true,        KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  NONE,    false,       KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  NONE,    true,        KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  CLOSE,   false,       CLOSE,       true),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  CLOSE,   true,        CLOSE,       true),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  KEEPA,   false,       KEEPA,       false),
        (`HTTP/1.0`, true,       KEEPA,    `HTTP/1.0`,  KEEPA,   true,        KEEPA,       false))
      // format: ON

      forAll(table)((reqProto, headReq, reqCH, resProto, resCH, resCD, renCH, close) ⇒
        ResponseRenderingContext(
          response = HttpResponse(200, headers = resCH.toList,
            entity = if (resCD) HttpEntity.CloseDelimited(
            ContentTypes.`text/plain(UTF-8)`,
            Source.single(ByteString("ENTITY")))
          else HttpEntity("ENTITY"), protocol = resProto),
          requestMethod = if (headReq) HttpMethods.HEAD else HttpMethods.GET,
          requestProtocol = reqProto,
          closeRequested = HttpMessage.connectionCloseExpected(reqProto, reqCH)) should renderTo(
            s"""${resProto.value} 200 OK
                 |Server: akka-http/1.0.0
                 |Date: Thu, 25 Aug 2011 09:10:29 GMT
                 |${renCH.fold("")(_ + "\n")}Content-Type: text/plain; charset=UTF-8
                 |${if (resCD) "" else "Content-Length: 6\n"}
                 |${if (headReq) "" else "ENTITY"}""", close))
    }
  }

  override def afterAll() = system.terminate()

  class TestSetup(val serverHeader: Option[Server] = Some(Server("akka-http/1.0.0")))
    extends HttpResponseRendererFactory(serverHeader, responseHeaderSizeHint = 64, NoLogging) {

    def awaitAtMost: FiniteDuration = 3.seconds

    def renderTo(expected: String): Matcher[HttpResponse] =
      renderToImpl(expected, checkClose = None) compose (ResponseRenderingContext(_))

    def renderTo(expected: String, close: Boolean): Matcher[ResponseRenderingContext] =
      renderToImpl(expected, checkClose = Some(close))

    def renderToImpl(expected: String, checkClose: Option[Boolean]): Matcher[ResponseRenderingContext] =
      equal(expected.stripMarginWithNewline("\r\n") → checkClose).matcher[(String, Option[Boolean])] compose { ctx ⇒
        val (wasCompletedFuture, resultFuture) =
          (Source.single(ctx) ++ Source.maybe[ResponseRenderingContext]) // never send upstream completion
            .via(renderer.named("renderer"))
            .map {
              case ResponseRenderingOutput.HttpData(bytes)      ⇒ bytes
              case _: ResponseRenderingOutput.SwitchToWebSocket ⇒ throw new IllegalStateException("Didn't expect websocket response")
            }
            .groupedWithin(1000, 100.millis)
            .watchTermination()(Keep.right)
            .toMat(Sink.head)(Keep.both).run()

        val wasCompleted: Option[Boolean] = checkClose match {
          case None ⇒ None
          case Some(close) ⇒
            // we try to find out if the renderer has already flagged completion even without the upstream being completed
            try {
              Await.ready(wasCompletedFuture, 100.millis)
              Some(true)
            } catch {
              case NonFatal(_) ⇒ Some(false)
            }
        }

        Await.result(resultFuture, awaitAtMost).reduceLeft(_ ++ _).utf8String → wasCompleted
      }

    override def currentTimeMillis() = DateTime(2011, 8, 25, 9, 10, 29).clicks // provide a stable date for testing
  }

  def source[T](elems: T*) = Source(elems.toList)
}
