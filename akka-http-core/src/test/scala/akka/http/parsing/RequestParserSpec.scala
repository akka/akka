/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import org.scalatest.matchers.Matcher
import org.reactivestreams.api.Producer
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream2.{ StreamProducer, Flow }
import akka.http.server.HttpServerPipeline
import akka.http.util._
import akka.http.model._
import headers._
import MediaTypes._
import HttpMethods._
import HttpProtocols._
import StatusCodes._
import HttpEntity._
import akka.stream2.impl.OperationProcessor

class RequestParserSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING
    akka.http.parsing.max-header-value-length = 32
    akka.http.parsing.max-uri-length = 20
    akka.http.parsing.max-content-length = 4000000000
    akka.http.parsing.incoming-auto-chunking-threshold-size = 20""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  val BOLT = HttpMethods.register(HttpMethod.custom("BOLT", safe = false, idempotent = true, entityAccepted = true))

  "The request parsing logic should" - {
    "properly parse a request" - {
      "with no headers and no body" in new Test {
        """GET / HTTP/1.0
          |
          |""" should parseTo(HttpRequest(protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with no headers and no body but remaining content" in new Test {
        """GET / HTTP/1.0
          |
          |POST /foo HTTP/1.0
          |
          |TRA""" /* beginning of TRACE request */ should parseTo(
          HttpRequest(GET, "/", protocol = `HTTP/1.0`),
          HttpRequest(POST, "/foo", protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true, true)
      }

      "with one header" in new Test {
        """GET / HTTP/1.1
          |Host: example.com
          |
          |""" should parseTo(HttpRequest(headers = List(Host("example.com"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with 3 headers and a body" in new Test {
        """POST /resource/yes HTTP/1.0
          |User-Agent: curl/7.19.7 xyz
          |Connection:keep-alive
          |Content-Type: text/plain; charset=UTF-8
          |Content-length:    17
          |
          |Shake your BOODY!""" should parseTo {
          HttpRequest(POST, "/resource/yes", List(`Content-Length`(17), `Content-Type`(ContentTypes.`text/plain(UTF-8)`),
            Connection("keep-alive"), `User-Agent`("curl/7.19.7 xyz")), "Shake your BOODY!", `HTTP/1.0`)
        }
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with 3 headers, a body and remaining content" in new Test {
        """POST /resource/yes HTTP/1.0
          |User-Agent: curl/7.19.7 xyz
          |Connection:keep-alive
          |Content-length:    17
          |
          |Shake your BOODY!GET / HTTP/1.0
          |
          |""" should parseTo(
          HttpRequest(POST, "/resource/yes", List(`Content-Length`(17), Connection("keep-alive"),
            `User-Agent`("curl/7.19.7 xyz")), HttpEntity(ContentTypes.`application/octet-stream`, "Shake your BOODY!"),
            `HTTP/1.0`),
          HttpRequest(protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(false, true)
      }

      "with multi-line headers" in new Test {
        """DELETE /abc HTTP/1.0
          |User-Agent: curl/7.19.7
          | abc
          |    xyz
          |Accept: */*
          |Connection: close,
          | fancy
          |
          |""" should parseTo {
          HttpRequest(DELETE, "/abc", List(Connection("close", "fancy"), Accept(MediaRanges.`*/*`),
            `User-Agent`("curl/7.19.7 abc xyz")), protocol = `HTTP/1.0`)
        }
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      //      "byte-by-byte" in new Test {
      //        """PUT /resource/yes HTTP/1.1
      //          |Content-length:    4
      //          |Host: x
      //          |
      //          |ABCDPATCH""".toCharArray.map(_.toString).toSeq should multiParseTo(
      //          HttpRequest(PUT, "/resource/yes", List(Host("x"), `Content-Length`(4)),
      //            HttpEntity(ContentTypes.`application/octet-stream`, "ABCD")))
      //        closeAfterResponseCompletion shouldEqual Seq(true)
      //      }

      //      "with a custom HTTP method" in {
      //        parse {
      //          """BOLT / HTTP/1.0
      //            |
      //            |"""
      //        } === Seq(BOLT, Uri("/"), `HTTP/1.0`, Nil, "", 'close)
      //      }
      //
      //      "with Content-Length > Int.MaxSize if autochunking is enabled" in {
      //        parse {
      //          """PUT /resource/yes HTTP/1.1
      //            |Content-length:    2147483649
      //            |Host: x
      //            |
      //            |"""
      //        } === Seq(PUT, Uri("/resource/yes"), `HTTP/1.1`, List(Host("x"), `Content-Length`(2147483649L)), 'dontClose)
      //      }
      //      "with several identical `Content-Type` headers" in {
      //        parse {
      //          """GET /data HTTP/1.1
      //            |Host: x
      //            |Content-Type: application/pdf
      //            |Content-Type: application/pdf
      //            |Content-Length: 0
      //            |
      //            |"""
      //        } === Seq(
      //          GET,
      //          Uri("/data"),
      //          `HTTP/1.1`,
      //          List(`Content-Length`(0), `Content-Type`(`application/pdf`), Host("x")),
      //          "", 'dontClose)
      //      }
      //    }
      //
      //    "properly parse a chunked request" in {
      //      val start =
      //        """PATCH /data HTTP/1.1
      //          |Transfer-Encoding: chunked
      //          |Connection: lalelu
      //          |Content-Type: application/pdf
      //          |Host: ping
      //          |
      //          |"""
      //      val startMatch = Seq(PATCH, Uri("/data"), `HTTP/1.1`, List(Host("ping"),
      //        `Content-Type`(`application/pdf`), Connection("lalelu"), `Transfer-Encoding`("chunked")), 'dontClose)
      //
      //      "request start" in {
      //        parse(start + "rest") === startMatch ++ Seq(400: StatusCode, "Illegal character 'r' in chunk start")
      //      }
      //
      //      "message chunk with and without extension" in {
      //        parse(start,
      //          """3
      //            |abc
      //            |10;some=stuff;bla
      //            |0123456789ABCDEF
      //            |""",
      //          "10;foo=",
      //          """bar
      //            |0123456789ABCDEF
      //            |10
      //            |0123456789""",
      //          """ABCDEF
      //            |dead""") === startMatch ++
      //          Seq("abc", "", 'dontClose,
      //            "0123456789ABCDEF", "some=stuff;bla", 'dontClose,
      //            "0123456789ABCDEF", "foo=bar", 'dontClose,
      //            "0123456789ABCDEF", "", 'dontClose)
      //      }
      //
      //      "message end" in {
      //        parse(start,
      //          """0
      //            |
      //            |""") === startMatch ++ Seq("", Nil, 'dontClose)
      //      }
      //
      //      "message end with extension, trailer and remaining content" in {
      //        parse(start,
      //          """000;nice=true
      //            |Foo: pip
      //            | apo
      //            |Bar: xyz
      //            |
      //            |GE""") === startMatch ++
      //          Seq("nice=true", List(RawHeader("Bar", "xyz"), RawHeader("Foo", "pip apo")), 'dontClose)
      //      }
      //    }
      //
      //    "properly auto-chunk" in {
      //      def start(contentSize: Int) =
      //        f"""GET /data HTTP/1.1
      //           |Content-Type: application/pdf
      //           |Host: ping
      //           |Content-Length: $contentSize%d
      //           |
      //           |"""
      //      def startMatch(contentSize: Int) =
      //        Seq(GET, Uri("/data"), `HTTP/1.1`, List(`Content-Length`(contentSize), Host("ping"), `Content-Type`(`application/pdf`)))
      //
      //      "full request if size < incoming-auto-chunking-threshold-size" in {
      //        parse(start(1) + "r") === startMatch(1) ++ Seq("r", 'dontClose)
      //      }
      //
      //      "request start" in {
      //        parse(start(25) + "rest") === startMatch(25) ++ Seq('dontClose,
      //          "rest", "", 'dontClose) // chunk
      //      }
      //
      //      "request start if complete message is already available" in {
      //        parse(start(25) + "rest1rest2rest3rest4rest5") ===
      //          startMatch(25) ++ Seq('dontClose, // chunkstart
      //            "rest1rest2rest3rest4rest5", "", 'dontClose, // chunk
      //            "", Nil, 'dontClose) // chunk end
      //      }
      //
      //      "request chunk" in {
      //        parse(start(25) + "rest1", "rest2") === startMatch(25) ++ Seq('dontClose, // chunkstart
      //          "rest1", "", 'dontClose, // chunk
      //          "rest2", "", 'dontClose) // chunk
      //      }
      //
      //      "request end" in {
      //        parse(start(25) + "rest1", "rest2", "rest3", "rest4", "rest5GET /data HTTP") ===
      //          startMatch(25) ++ Seq('dontClose, // chunkstart
      //            "rest1", "", 'dontClose, // chunk
      //            "rest2", "", 'dontClose, // chunk
      //            "rest3", "", 'dontClose, // chunk
      //            "rest4", "", 'dontClose, // chunk
      //            "rest5", "", 'dontClose, // chunk
      //            "", Nil, 'dontClose) // chunk end
      //      }
      //    }
      //
      //    "reject a message chunk with" in {
      //      val start =
      //        """PATCH /data HTTP/1.1
      //          |Transfer-Encoding: chunked
      //          |Connection: lalelu
      //          |Host: ping
      //          |
      //          |"""
      //
      //      "an illegal char after chunk size" in {
      //        parse(start,
      //          """15 ;
      //            |""").takeRight(2) === Seq(BadRequest, "Illegal character ' ' in chunk start")
      //      }
      //
      //      "an illegal char in chunk size" in {
      //        parse(start, "bla").takeRight(2) === Seq(BadRequest, "Illegal character 'l' in chunk start")
      //      }
      //
      //      "too-long chunk extension" in {
      //        parse(start, "3;" + ("x" * 257)).takeRight(2) ===
      //          Seq(BadRequest, "HTTP chunk extension length exceeds configured limit of 256 characters")
      //      }
      //
      //      "too-large chunk size" in {
      //        parse(start,
      //          """1a2b3c4d5e
      //            |""").takeRight(2) === Seq(BadRequest, "HTTP chunk size exceeds the configured limit of 1048576 bytes")
      //      }
      //
      //      "an illegal chunk termination" in {
      //        parse(start,
      //          """3
      //            |abcde""").takeRight(2) === Seq(BadRequest, "Illegal chunk termination")
      //      }
      //
      //      "an illegal header in the trailer" in {
      //        parse(start,
      //          """0
      //            |F@oo: pip""").takeRight(2) === Seq(BadRequest, "Illegal character '@' in header name")
      //      }
      //    }
      //
      //    "reject a request with" in {
      //      "an illegal HTTP method" in {
      //        parse("get ") === Seq(NotImplemented, "Unsupported HTTP method: get")
      //        parse("GETX ") === Seq(NotImplemented, "Unsupported HTTP method: GETX")
      //      }
      //
      //      "two Content-Length headers" in {
      //        parse {
      //          """GET / HTTP/1.1
      //            |Content-Length: 3
      //            |Content-Length: 3
      //            |
      //            |foo"""
      //        } === Seq(BadRequest, "HTTP message must not contain more than one Content-Length header")
      //      }
      //
      //      "a too-long URI" in {
      //        parse("GET /23456789012345678901 HTTP/1.1") ===
      //          Seq(RequestUriTooLong, "URI length exceeds the configured limit of 20 characters")
      //      }
      //
      //      "HTTP version 1.2" in {
      //        parse {
      //          """GET / HTTP/1.2
      //            |"""
      //        } ===
      //          Seq(HTTPVersionNotSupported, "The server does not support the HTTP protocol version used in the request.")
      //      }
      //
      //      "with an illegal char in a header name" in {
      //        parse {
      //          """|GET / HTTP/1.1
      //            |User@Agent: curl/7.19.7"""
      //        } === Seq(BadRequest, "Illegal character '@' in header name")
      //      }
      //
      //      "with a too-long header name" in {
      //        parse {
      //          """|GET / HTTP/1.1
      //            |UserxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxAgent: curl/7.19.7"""
      //        } === Seq(BadRequest, "HTTP header name exceeds the configured limit of 64 characters")
      //      }
      //
      //      "with a too-long header-value" in {
      //        parse {
      //          """|GET / HTTP/1.1
      //            |Fancy: 123456789012345678901234567890123"""
      //        } === Seq(BadRequest, "HTTP header value exceeds the configured limit of 32 characters")
      //      }
      //
      //      "with an invalid Content-Length header value" in {
      //        parse {
      //          """|GET / HTTP/1.0
      //            |Content-Length: 1.5
      //            |
      //            |abc"""
      //        } === Seq(BadRequest, "Illegal `Content-Length` header value")
      //      }
      //
      //      "with Content-Length > Int.MaxSize if autochunking is disabled" in {
      //        val request =
      //          """PUT /resource/yes HTTP/1.1
      //            |Content-length:    2147483649
      //            |Host: x
      //            |
      //            |"""
      //        val parser = new HttpRequestParser(ParserSettings(system).copy(autoChunkingThreshold = Long.MaxValue))()
      //        parse(parser)(request) === Seq(400: StatusCode, "Content-Length > Int.MaxSize not supported for non-(auto)-chunked requests")
      //      }
      //
      //      "with Content-Length > Long.MaxSize" in {
      //        // content-length = (Long.MaxValue + 1) * 10, which is 0 when calculated overflow
      //        parse {
      //          """PUT /resource/yes HTTP/1.1
      //            |Content-length: 92233720368547758080
      //            |Host: x
      //            |
      //            |"""
      //        } === Seq(400: StatusCode, "Illegal `Content-Length` header value")
      //      }
    }
  }

  override def afterAll() = system.shutdown()

  private[http] class Test {
    var closeAfterResponseCompletion = Seq.empty[Boolean]

    def parseTo(expected: HttpRequest*): Matcher[String] =
      multiParseTo(expected: _*).compose(_ :: Nil)

    def multiParseTo(expected: HttpRequest*): Matcher[Seq[String]] = multiParseTo(newParser, expected: _*)

    def multiParseTo(parser: HttpRequestParser, expected: HttpRequest*): Matcher[Seq[String]] =
      rawMultiParseTo(parser, expected: _*).compose((_: Seq[String]) map prep)

    def rawMultiParseTo(parser: HttpRequestParser, expected: HttpRequest*) =
      equal(expected).matcher[Seq[HttpRequest]] compose { input: Seq[String] ⇒
        val future =
          Flow(input)
            .map(ByteString.apply)
            .transform(parser)
            .split(HttpServerPipeline.splitParserOutput)
            .headAndTail
            .collect {
              case (x: ParserOutput.RequestStart, entityParts) ⇒
                closeAfterResponseCompletion :+= x.closeAfterResponseCompletion
                HttpServerPipeline.constructRequest(x, entityParts)
            }
            .mapConcat { response ⇒ compactEntity(response.entity).map(response.withEntity) }
            .drainToSeq
        Await.result(future, 100.millis)
      }

    private def newParser = new HttpRequestParser(ParserSettings(system))()

    private def compactEntity(entity: HttpEntity): Future[HttpEntity] =
      entity match {
        case x: HttpEntity.Default ⇒ compactEntityData(x.data).map(compacted ⇒ x.copy(data = compacted))
        case x: HttpEntity.Chunked ⇒ compactEntityChunks(x.chunks).map(compacted ⇒ x.copy(chunks = compacted))
        case _                     ⇒ fail("Unexpected " + entity)
      }

    private def compactEntityData(data: Producer[ByteString]): Future[Producer[ByteString]] =
      // compact and convert to `StreamProducer.ForIterable` which provides value equality
      Flow(data)
        .fold(ByteString.empty)(_ ++ _)
        .mapConcat(bytes ⇒ if (bytes.isEmpty) None else Some(bytes))
        .headFuture
        .map(StreamProducer.of(_)) // creates `StreamProducer.ForIterable`
        .recover { case _: NoSuchElementException ⇒ StreamProducer.empty[ByteString] }

    private def compactEntityChunks(data: Producer[ChunkStreamPart]): Future[Producer[ChunkStreamPart]] =
      Flow(data).drainToSeq.map(StreamProducer.apply(_))

    private def prep(response: String) = response.stripMarginWithNewline("\r\n")
  }
}
