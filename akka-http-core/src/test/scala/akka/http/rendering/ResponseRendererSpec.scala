/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.rendering

import com.typesafe.config.{ Config, ConfigFactory }
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.{ FreeSpec, Matchers, BeforeAndAfterAll }
import org.scalatest.matchers.Matcher
import akka.actor.ActorSystem
import akka.event.NoLogging
import akka.stream2.{ StreamProducer, Flow }
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._
import akka.util.ByteString
import HttpEntity._

class ResponseRendererSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  val ServerOnTheMove = StatusCodes.registerCustom(330, "Server on the move")

  "The response preparation logic should properly render" - {
    "an unchunked response" - {
      "with status 200, no headers and no body" in new TestSetup() {
        HttpResponse(200) should renderTo {
          """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }

      "with status 304, a few headers and no body" in new TestSetup() {
        HttpResponse(304, List(RawHeader("X-Fancy", "of course"), RawHeader("Age", "0"))) should renderTo {
          """HTTP/1.1 304 Not Modified
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |X-Fancy: of course
            |Age: 0
            |Content-Length: 0
            |
            |"""
        }
      }

      "with status 400, a few headers and a body" in new TestSetup() {
        HttpResponse(400, List(RawHeader("Age", "30"), Connection("Keep-Alive")), "Small f*ck up overhere!") should renderTo {
          """HTTP/1.1 400 Bad Request
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Age: 30
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 23
            |
            |Small f*ck up overhere!"""
        }
      }

      "with status 400, a few headers and a body with an explicitly suppressed Content Type header" in new TestSetup() {
        HttpResponse(400, List(RawHeader("Age", "30"), Connection("Keep-Alive")),
          HttpEntity(contentType = ContentTypes.NoContentType, "Small f*ck up overhere!")) should renderTo {
            """HTTP/1.1 400 Bad Request
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Age: 30
            |Content-Length: 23
            |
            |Small f*ck up overhere!"""
          }
      }

      "with a custom status code, no headers and no body" in new TestSetup() {
        HttpResponse(ServerOnTheMove) should renderTo {
          """HTTP/1.1 330 Server on the move
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 0
            |
            |"""
        }
      }

      "to a HEAD request" in new TestSetup() {
        ResponseRenderingContext(
          requestMethod = HttpMethods.HEAD,
          response = HttpResponse(
            headers = List(RawHeader("Age", "30"), Connection("Keep-Alive")),
            entity = "Small f*ck up overhere!")) should renderTo(
            """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Age: 30
            |Content-Type: text/plain; charset=UTF-8
            |Content-Length: 23
            |
            |""", close = false)
      }
    }

    "a chunked response" - {
      "with empty entity" in new TestSetup() {
        HttpResponse(200, List(RawHeader("Age", "30")),
          Chunked(ContentTypes.`application/json`, StreamProducer.empty)) should renderTo {
            """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Age: 30
            |
            |"""
          }
      }

      "with one chunk and no explicit LastChunk" in new TestSetup() {
        HttpResponse(entity = Chunked(ContentTypes.`text/plain(UTF-8)`,
          StreamProducer.of(Chunk(ByteString("Yahoooo"))))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Type: text/plain; charset=UTF-8
            |Transfer-Encoding: chunked
            |
            |7
            |Yahoooo
            |0
            |
            |"""
        }
      }

      "with one chunk and an explicit LastChunk" in new TestSetup() {
        HttpResponse(entity = Chunked(ContentTypes.`text/plain(UTF-8)`,
          StreamProducer.of(Chunk(ByteString("body123"), """key=value;another="tl;dr""""),
            LastChunk("foo=bar", List(RawHeader("Age", "30"), RawHeader("Cache-Control", "public")))))) should renderTo {
          """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Type: text/plain; charset=UTF-8
            |Transfer-Encoding: chunked
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
    }

    "a chunkless chunked response" - {
      "with empty entity and explicit Content-Length" in
        new TestSetup(chunklessStreaming = true) {
          HttpResponse(200, List(RawHeader("Age", "30"), `Content-Length`(0)),
            Chunked(ContentTypes.`application/json`, StreamProducer.empty)) should renderTo {
              """HTTP/1.1 200 OK
              |Server: spray-can/1.0.0
              |Date: Thu, 25 Aug 2011 09:10:29 GMT
              |Age: 30
              |Content-Length: 0
              |
              |"""
            }
        }

      "with one chunk and correct explicit Content-Length" in new TestSetup(chunklessStreaming = true) {
        HttpResponse(200, List(RawHeader("Age", "30"), `Content-Length`(7)),
          Chunked(ContentTypes.`application/json`, StreamProducer.of(Chunk(ByteString("body123"))))) should renderTo {
            """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Age: 30
            |Content-Length: 7
            |Content-Type: application/json; charset=UTF-8
            |
            |body123"""
          }
      }

      "with one chunk and no Content-Length" in new TestSetup(chunklessStreaming = true) {
        ResponseRenderingContext(HttpResponse(entity = Chunked(ContentTypes.`application/json`,
          StreamProducer.of(Chunk(ByteString("body1234567890123456")))))) should renderTo(
          """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Connection: close
            |Content-Type: application/json; charset=UTF-8
            |
            |body1234567890123456""", close = true)
      }

      "with one chunk and incorrect (too large) Content-Length" in new TestSetup(chunklessStreaming = true) {
        the[RuntimeException] thrownBy {
          HttpResponse(200, List(`Content-Length`(10)), Chunked(ContentTypes.`application/json`,
            StreamProducer.of(Chunk(ByteString("body123"))))) should renderTo("")
        } should have message "Chunkless streaming response has manual header `Content-Length: 10` but entity chunk " +
          "stream amounts to 3 bytes less"
      }

      "with one chunk and incorrect (too small) Content-Length" in new TestSetup(chunklessStreaming = true) {
        the[RuntimeException] thrownBy {
          HttpResponse(200, List(`Content-Length`(5)), Chunked(ContentTypes.`application/json`,
            StreamProducer.of(Chunk(ByteString("body123"))))) should renderTo("")
        } should have message "Chunkless streaming response has manual header `Content-Length: 5` but entity chunk " +
          "stream amounts to more bytes"
      }

      "with two chunks and no Content-Length (HTTP/1.0)" in new TestSetup(chunklessStreaming = true) {
        ResponseRenderingContext(
          requestProtocol = HttpProtocols.`HTTP/1.0`,
          response = HttpResponse(entity = Chunked(ContentTypes.`application/json`,
            StreamProducer.of(Chunk(ByteString("abc")), Chunk(ByteString("defg")))))) should renderTo(
            """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Type: application/json; charset=UTF-8
            |
            |abcdefg""", close = true)
      }

      "with two chunks and explicit Content-Length (HTTP/1.0)" in new TestSetup(chunklessStreaming = true) {
        ResponseRenderingContext(
          requestProtocol = HttpProtocols.`HTTP/1.0`,
          response = HttpResponse(200, List(`Content-Length`(7)), Chunked(ContentTypes.`application/json`,
            StreamProducer.of(Chunk(ByteString("abc")), Chunk(ByteString("defg")))))) should renderTo(
            """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Content-Length: 7
            |Connection: Keep-Alive
            |Content-Type: application/json; charset=UTF-8
            |
            |abcdefg""", close = false)
      }

      "with one chunk and an explicit LastChunk" in new TestSetup(chunklessStreaming = true) {
        ResponseRenderingContext(HttpResponse(entity = Chunked(ContentTypes.`text/plain(UTF-8)`,
          StreamProducer.of(Chunk(ByteString("body123"), """key=value;another="tl;dr""""),
            LastChunk("foo=bar", List(RawHeader("Age", "30"), RawHeader("Cache-Control", "public"))))))) should renderTo(
          """HTTP/1.1 200 OK
            |Server: spray-can/1.0.0
            |Date: Thu, 25 Aug 2011 09:10:29 GMT
            |Connection: close
            |Content-Type: text/plain; charset=UTF-8
            |
            |body123""", close = true)
      }
    }

    "The 'Connection' header should be rendered correctly" in new TestSetup() {
      import org.scalatest.prop.TableDrivenPropertyChecks._
      import HttpProtocols._

      def NONE: Option[String] = None
      // format: OFF
      val table = Table(
        ("Client Version", "Request"          , "Response"     , "Rendered"        , "Close"),
        (`HTTP/1.1`      , NONE               , NONE           , NONE              , false),
        (`HTTP/1.1`      , Some("close")      , NONE           , Some("close")     , true),
        (`HTTP/1.1`      , Some("Keep-Alive") , NONE           , NONE              , false),
        (`HTTP/1.0`      , NONE               , NONE           , NONE              , true),
        (`HTTP/1.0`      , Some("close")      , NONE           , NONE              , true),
        (`HTTP/1.0`      , Some("Keep-Alive") , NONE           , Some("Keep-Alive"), false),
        (`HTTP/1.1`      , NONE               , Some("close")  , Some("close")     , true))
      // format: ON

      forAll(table) { (reqProto, reqCH, resCH, renCH, close) ⇒
        ResponseRenderingContext(
          response = HttpResponse(200, headers = resCH.map(h ⇒ List(Connection(h))) getOrElse Nil),
          requestProtocol = reqProto,
          closeAfterResponseCompletion = HttpMessage.connectionCloseExpected(reqProto, reqCH map (Connection(_)))) should renderTo(
            expected = renCH match {
              case Some(connection) ⇒
                s"""HTTP/1.1 200 OK
                         |Server: spray-can/1.0.0
                         |Date: Thu, 25 Aug 2011 09:10:29 GMT
                         |Connection: $connection
                         |Content-Length: 0
                         |
                         |"""
              case None ⇒
                """HTTP/1.1 200 OK
                  |Server: spray-can/1.0.0
                  |Date: Thu, 25 Aug 2011 09:10:29 GMT
                  |Content-Length: 0
                  |
                  |"""
            }, close = close)
      }
    }
  }

  override def afterAll() = system.shutdown()

  class TestSetup(val serverHeaderValue: String = "spray-can/1.0.0",
                  val chunklessStreaming: Boolean = false,
                  val transparentHeadRequests: Boolean = true)
    extends HttpResponseRendererFactory(serverHeaderValue.toOption.map(Server(_)), chunklessStreaming,
      responseHeaderSizeHint = 64, NoLogging) {

    def renderTo(expected: String): Matcher[HttpResponse] =
      renderTo(expected, close = false) compose (ResponseRenderingContext(_))

    def renderTo(expected: String, close: Boolean): Matcher[ResponseRenderingContext] =
      equal(expected.stripMarginWithNewline("\r\n") -> close).matcher[(String, Boolean)] compose { input ⇒
        val renderer = newRenderer
        val byteStringProducer :: Nil = renderer.onNext(input)
        val future = Flow(byteStringProducer).drainToSeq.map(_.reduceLeft(_ ++ _).utf8String)
        Await.result(future, 250.millis) -> renderer.isComplete
      }

    override def dateTime(now: Long) = DateTime(2011, 8, 25, 9, 10, 29) // provide a stable date for testing
  }
}
