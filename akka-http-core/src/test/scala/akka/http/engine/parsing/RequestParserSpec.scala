/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, FreeSpec, Matchers }
import org.scalatest.matchers.Matcher
import akka.stream.scaladsl._
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.FlattenStrategy
import akka.stream.FlowMaterializer
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.http.util._
import akka.http.model._
import headers._
import MediaTypes._
import HttpMethods._
import HttpProtocols._
import StatusCodes._
import HttpEntity._
import ParserOutput._
import FastFuture._

class RequestParserSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING
    akka.http.parsing.max-header-value-length = 32
    akka.http.parsing.max-uri-length = 20
    akka.http.parsing.max-content-length = 4000000000""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  val BOLT = HttpMethod.custom("BOLT", safe = false, idempotent = true, entityAccepted = true)
  implicit val materializer = FlowMaterializer()

  "The request parsing logic should" - {
    "properly parse a request" - {
      "with no headers and no body" in new Test {
        """GET / HTTP/1.0
          |
          |""" should parseTo(HttpRequest(protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with no headers and no body but remaining content" in new Test {
        Seq("""GET / HTTP/1.0
          |
          |POST /foo HTTP/1.0
          |
          |TRA""") /* beginning of TRACE request */ should generalMultiParseTo(
          Right(HttpRequest(GET, "/", protocol = `HTTP/1.0`)),
          Right(HttpRequest(POST, "/foo", protocol = `HTTP/1.0`)),
          Left(MessageStartError(StatusCodes.BadRequest, ErrorInfo("Illegal HTTP message start"))))
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
          HttpRequest(POST, "/resource/yes", List(Connection("keep-alive"), `User-Agent`("curl/7.19.7 xyz")),
            "Shake your BOODY!", `HTTP/1.0`)
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
          HttpRequest(POST, "/resource/yes", List(Connection("keep-alive"), `User-Agent`("curl/7.19.7 xyz")),
            "Shake your BOODY!".getBytes, `HTTP/1.0`),
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

      "byte-by-byte" in new Test {
        prep {
          """PUT /resource/yes HTTP/1.1
            |Content-length:    4
            |Host: x
            |
            |ABCDPATCH"""
        }.toCharArray.map(_.toString).toSeq should rawMultiParseTo(
          HttpRequest(PUT, "/resource/yes", List(Host("x")), "ABCD".getBytes))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with a custom HTTP method" in new Test {
        override protected def parserSettings: ParserSettings =
          super.parserSettings.withCustomMethods(BOLT)

        """BOLT / HTTP/1.0
          |
          |""" should parseTo(HttpRequest(BOLT, "/", protocol = `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "with a funky `Transfer-Encoding` header" in new Test {
        """GET / HTTP/1.1
          |Transfer-Encoding: foo, chunked, bar
          |Host: x
          |
          |""" should parseTo(HttpRequest(GET, "/", List(`Transfer-Encoding`(TransferEncodings.Extension("foo"),
          TransferEncodings.chunked, TransferEncodings.Extension("bar")), Host("x"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "with several identical `Content-Type` headers" in new Test {
        """GET /data HTTP/1.1
          |Host: x
          |Content-Type: application/pdf
          |Content-Type: application/pdf
          |Content-Length: 0
          |
          |""" should parseTo(HttpRequest(GET, "/data", List(Host("x")), HttpEntity(`application/pdf`, "")))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "properly parse a chunked request" - {
        val start =
          """PATCH /data HTTP/1.1
            |Transfer-Encoding: chunked
            |Connection: lalelu
            |Content-Type: application/pdf
            |Host: ping
            |
            |"""
        val baseRequest = HttpRequest(PATCH, "/data", List(Host("ping"), Connection("lalelu")))

        "request start" in new Test {
          Seq(start, "rest") should generalMultiParseTo(
            Right(baseRequest.withEntity(HttpEntity.Chunked(`application/pdf`, source()))),
            Left(EntityStreamError(ErrorInfo("Illegal character 'r' in chunk start"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "message chunk with and without extension" in new Test {
          Seq(start +
            """3
              |abc
              |10;some=stuff;bla
              |0123456789ABCDEF
              |""",
            "10;foo=",
            """bar
              |0123456789ABCDEF
              |A
              |0123456789""",
            """
              |0
              |
              |""") should generalMultiParseTo(
              Right(baseRequest.withEntity(Chunked(`application/pdf`, source(
                Chunk(ByteString("abc")),
                Chunk(ByteString("0123456789ABCDEF"), "some=stuff;bla"),
                Chunk(ByteString("0123456789ABCDEF"), "foo=bar"),
                Chunk(ByteString("0123456789"), ""),
                LastChunk)))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "message end" in new Test {
          Seq(start,
            """0
              |
              |""") should generalMultiParseTo(
              Right(baseRequest.withEntity(Chunked(`application/pdf`, source(LastChunk)))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "message end with extension and trailer" in new Test {
          Seq(start,
            """000;nice=true
              |Foo: pip
              | apo
              |Bar: xyz
              |
              |""") should generalMultiParseTo(
              Right(baseRequest.withEntity(Chunked(`application/pdf`,
                source(LastChunk("nice=true", List(RawHeader("Bar", "xyz"), RawHeader("Foo", "pip apo"))))))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "don't overflow the stack for large buffers of chunks" in new Test {
          override val awaitAtMost = 3000.millis

          val x = NotEnoughDataException
          val numChunks = 15000 // failed starting from 4000 with sbt started with `-Xss2m`
          val oneChunk = "1\r\nz\n"
          val manyChunks = (oneChunk * numChunks) + "0\r\n"

          val parser = newParser
          val result = multiParse(newParser)(Seq(prep(start + manyChunks)))
          val HttpEntity.Chunked(_, chunks) = result.head.right.get.req.entity
          val strictChunks = chunks.collectAll.awaitResult(awaitAtMost)
          strictChunks.size shouldEqual numChunks
        }
      }

      "properly parse a chunked request with additional transfer encodings" in new Test {
        """PATCH /data HTTP/1.1
          |Transfer-Encoding: fancy, chunked
          |Content-Type: application/pdf
          |Host: ping
          |
          |0
          |
          |""" should parseTo(HttpRequest(PATCH, "/data", List(`Transfer-Encoding`(TransferEncodings.Extension("fancy")),
          Host("ping")), HttpEntity.Chunked(`application/pdf`, source(LastChunk))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "support `rawRequestUriHeader` setting" in new Test {
        override protected def newParser: HttpRequestParser =
          new HttpRequestParser(parserSettings, rawRequestUriHeader = true, _headerParser = HttpHeaderParser(parserSettings)())

        """GET /f%6f%6fbar?q=b%61z HTTP/1.1
          |Host: ping
          |Content-Type: application/pdf
          |
          |""" should parseTo(
          HttpRequest(
            GET,
            "/foobar?q=baz",
            List(
              `Raw-Request-URI`("/f%6f%6fbar?q=b%61z"),
              Host("ping")),
            HttpEntity.empty(`application/pdf`)))
      }

      "reject a message chunk with" - {
        val start =
          """PATCH /data HTTP/1.1
            |Transfer-Encoding: chunked
            |Connection: lalelu
            |Host: ping
            |
            |"""
        val baseRequest = HttpRequest(PATCH, "/data", List(Host("ping"), Connection("lalelu")),
          HttpEntity.Chunked(`application/octet-stream`, source()))

        "an illegal char after chunk size" in new Test {
          Seq(start,
            """15 ;
              |""") should generalMultiParseTo(Right(baseRequest),
              Left(EntityStreamError(ErrorInfo("Illegal character ' ' in chunk start"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "an illegal char in chunk size" in new Test {
          Seq(start, "bla") should generalMultiParseTo(Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("Illegal character 'l' in chunk start"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "too-long chunk extension" in new Test {
          Seq(start, "3;" + ("x" * 257)) should generalMultiParseTo(Right(baseRequest),
            Left(EntityStreamError(ErrorInfo("HTTP chunk extension length exceeds configured limit of 256 characters"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "too-large chunk size" in new Test {
          Seq(start,
            """1a2b3c4d5e
               |""") should generalMultiParseTo(Right(baseRequest),
              Left(EntityStreamError(ErrorInfo("HTTP chunk size exceeds the configured limit of 1048576 bytes"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "an illegal chunk termination" in new Test {
          Seq(start,
            """3
              |abcde""") should generalMultiParseTo(Right(baseRequest),
              Left(EntityStreamError(ErrorInfo("Illegal chunk termination"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }

        "an illegal header in the trailer" in new Test {
          Seq(start,
            """0
              |F@oo: pip""") should generalMultiParseTo(Right(baseRequest),
              Left(EntityStreamError(ErrorInfo("Illegal character '@' in header name"))))
          closeAfterResponseCompletion shouldEqual Seq(false)
        }
      }

      "reject a request with" - {
        "an illegal HTTP method" in new Test {
          "get " should parseToError(NotImplemented, ErrorInfo("Unsupported HTTP method", "get"))
          "GETX " should parseToError(NotImplemented, ErrorInfo("Unsupported HTTP method", "GETX"))
        }

        "a too long HTTP method" in new Test {
          "ABCDEFGHIJKLMNOPQ " should
            parseToError(BadRequest,
              ErrorInfo(
                "Unsupported HTTP method",
                "HTTP method too long (started with 'ABCDEFGHIJKLMNOP'). Increase `akka.http.server.parsing.max-method-length` to support HTTP methods with more characters."))
        }

        "two Content-Length headers" in new Test {
          """GET / HTTP/1.1
            |Content-Length: 3
            |Content-Length: 4
            |
            |foo""" should parseToError(BadRequest,
            ErrorInfo("HTTP message must not contain more than one Content-Length header"))
        }

        "a too-long URI" in new Test {
          "GET /23456789012345678901 HTTP/1.1" should parseToError(RequestUriTooLong,
            ErrorInfo("URI length exceeds the configured limit of 20 characters"))
        }

        "HTTP version 1.2" in new Test {
          """GET / HTTP/1.2
              |""" should parseToError(HTTPVersionNotSupported,
            ErrorInfo("The server does not support the HTTP protocol version used in the request."))
        }

        "with an illegal char in a header name" in new Test {
          """GET / HTTP/1.1
            |User@Agent: curl/7.19.7""" should parseToError(BadRequest, ErrorInfo("Illegal character '@' in header name"))
        }

        "with a too-long header name" in new Test {
          """|GET / HTTP/1.1
            |UserxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxAgent: curl/7.19.7""" should parseToError(
            BadRequest, ErrorInfo("HTTP header name exceeds the configured limit of 64 characters"))
        }

        "with a too-long header-value" in new Test {
          """|GET / HTTP/1.1
            |Fancy: 123456789012345678901234567890123""" should parseToError(BadRequest,
            ErrorInfo("HTTP header value exceeds the configured limit of 32 characters"))
        }

        "with an invalid Content-Length header value" in new Test {
          """GET / HTTP/1.0
            |Content-Length: 1.5
            |
            |abc""" should parseToError(BadRequest, ErrorInfo("Illegal `Content-Length` header value"))
        }

        "with Content-Length > Long.MaxSize" in new Test {
          // content-length = (Long.MaxValue + 1) * 10, which is 0 when calculated overflow
          """PUT /resource/yes HTTP/1.1
            |Content-length: 92233720368547758080
            |Host: x
            |
            |""" should parseToError(400: StatusCode, ErrorInfo("`Content-Length` header value must not exceed 63-bit integer range"))
        }
      }
    }
  }

  override def afterAll() = system.shutdown()

  private class Test {
    def awaitAtMost: FiniteDuration = 250.millis
    var closeAfterResponseCompletion = Seq.empty[Boolean]

    class StrictEqualHttpRequest(val req: HttpRequest) {
      override def equals(other: scala.Any): Boolean = other match {
        case other: StrictEqualHttpRequest ⇒
          this.req.copy(entity = HttpEntity.Empty) == other.req.copy(entity = HttpEntity.Empty) &&
            this.req.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost) ==
            other.req.entity.toStrict(awaitAtMost).awaitResult(awaitAtMost)
      }

      override def toString = req.toString
    }

    def strictEqualify[T](x: Either[T, HttpRequest]): Either[T, StrictEqualHttpRequest] =
      x.right.map(new StrictEqualHttpRequest(_))

    def parseTo(expected: HttpRequest*): Matcher[String] =
      multiParseTo(expected: _*).compose(_ :: Nil)

    def multiParseTo(expected: HttpRequest*): Matcher[Seq[String]] = multiParseTo(newParser, expected: _*)
    def multiParseTo(parser: HttpRequestParser, expected: HttpRequest*): Matcher[Seq[String]] =
      rawMultiParseTo(parser, expected: _*).compose(_ map prep)

    def rawMultiParseTo(expected: HttpRequest*): Matcher[Seq[String]] =
      rawMultiParseTo(newParser, expected: _*)
    def rawMultiParseTo(parser: HttpRequestParser, expected: HttpRequest*): Matcher[Seq[String]] =
      generalRawMultiParseTo(parser, expected.map(Right(_)): _*)

    def parseToError(status: StatusCode, info: ErrorInfo): Matcher[String] =
      generalMultiParseTo(Left(MessageStartError(status, info))).compose(_ :: Nil)

    def generalMultiParseTo(expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(expected: _*).compose(_ map prep)

    def generalRawMultiParseTo(expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(newParser, expected: _*)
    def generalRawMultiParseTo(parser: HttpRequestParser,
                               expected: Either[RequestOutput, HttpRequest]*): Matcher[Seq[String]] =
      equal(expected.map(strictEqualify))
        .matcher[Seq[Either[RequestOutput, StrictEqualHttpRequest]]] compose multiParse(parser)

    def multiParse(parser: HttpRequestParser)(input: Seq[String]): Seq[Either[RequestOutput, StrictEqualHttpRequest]] =
      Source(input.toList)
        .map(ByteString.apply)
        .section(name("parser"))(_.transform(() ⇒ parser))
        .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x.isInstanceOf[EntityStreamError])
        .headAndTail
        .collect {
          case (RequestStart(method, uri, protocol, headers, createEntity, _, close), entityParts) ⇒
            closeAfterResponseCompletion :+= close
            Right(HttpRequest(method, uri, headers, createEntity(entityParts), protocol))
          case (x @ (MessageStartError(_, _) | EntityStreamError(_)), _) ⇒ Left(x)
        }
        .map { x ⇒
          Source {
            x match {
              case Right(request) ⇒ compactEntity(request.entity).fast.map(x ⇒ Right(request.withEntity(x)))
              case Left(error)    ⇒ FastFuture.successful(Left(error))
            }
          }
        }
        .flatten(FlattenStrategy.concat)
        .map(strictEqualify)
        .collectAll
        .awaitResult(awaitAtMost)

    protected def parserSettings: ParserSettings = ParserSettings(system)
    protected def newParser = new HttpRequestParser(parserSettings, false, HttpHeaderParser(parserSettings)())

    private def compactEntity(entity: RequestEntity): Future[RequestEntity] =
      entity match {
        case x: Chunked ⇒ compactEntityChunks(x.chunks).fast.map(compacted ⇒ x.copy(chunks = source(compacted: _*)))
        case _          ⇒ entity.toStrict(awaitAtMost)
      }

    private def compactEntityChunks(data: Source[ChunkStreamPart]): Future[Seq[ChunkStreamPart]] =
      data.collectAll
        .fast.recover { case _: NoSuchElementException ⇒ Nil }

    def prep(response: String) = response.stripMarginWithNewline("\r\n")
  }

  def source[T](elems: T*): Source[T] = Source(elems.toList)
}
