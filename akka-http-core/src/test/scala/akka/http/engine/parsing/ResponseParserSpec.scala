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

class ResponseParserSpec extends FreeSpec with Matchers with BeforeAndAfterAll {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING
    akka.http.parsing.max-response-reason-length = 21""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  import system.dispatcher

  implicit val materializer = FlowMaterializer()
  val ServerOnTheMove = StatusCodes.custom(331, "Server on the move")

  "The response parsing logic should" - {
    "properly parse" - {

      // http://tools.ietf.org/html/rfc7230#section-3.3.3
      "a 200 response to a HEAD request" in new Test {
        """HTTP/1.1 200 OK
          |
          |""" should parseTo(HEAD, HttpResponse())
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      // http://tools.ietf.org/html/rfc7230#section-3.3.3
      "a 204 response" in new Test {
        """HTTP/1.1 204 OK
          |
          |""" should parseTo(HttpResponse(NoContent))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "a response with a custom status code" in new Test {
        override def parserSettings: ParserSettings =
          super.parserSettings.withCustomStatusCodes(ServerOnTheMove)

        """HTTP/1.1 331 Server on the move
          |Content-Length: 0
          |
          |""" should parseTo(HttpResponse(ServerOnTheMove))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "a response funky `Transfer-Encoding` header" in new Test {
        override def parserSettings: ParserSettings =
          super.parserSettings.withCustomStatusCodes(ServerOnTheMove)

        """HTTP/1.1 331 Server on the move
          |Transfer-Encoding: foo, chunked, bar
          |Content-Length: 0
          |
          |""" should parseTo(HttpResponse(ServerOnTheMove, List(`Transfer-Encoding`(TransferEncodings.Extension("foo"),
          TransferEncodings.chunked, TransferEncodings.Extension("bar")))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "a response with one header, a body, but no Content-Length header" in new Test {
        """HTTP/1.0 404 Not Found
          |Host: api.example.com
          |
          |Foobs""" should parseTo(HttpResponse(NotFound, List(Host("api.example.com")), "Foobs".getBytes, `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "a response with one header, no body, and no Content-Length header" in new Test {
        """HTTP/1.0 404 Not Found
          |Host: api.example.com
          |
          |""" should parseTo(HttpResponse(NotFound, List(Host("api.example.com")),
          HttpEntity.empty(ContentTypes.`application/octet-stream`), `HTTP/1.0`))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "a response with 3 headers, a body and remaining content" in new Test {
        Seq("""HTTP/1.1 500 Internal Server Error
          |User-Agent: curl/7.19.7 xyz
          |Connection:close
          |Content-Length: 17
          |Content-Type: text/plain; charset=UTF-8
          |
          |Sh""", "ake your BOODY!HTTP/1.") should generalMultiParseTo(
          Right(HttpResponse(InternalServerError, List(Connection("close"), `User-Agent`("curl/7.19.7 xyz")),
            "Shake your BOODY!")))
        closeAfterResponseCompletion shouldEqual Seq(true)
      }

      "a split response (parsed byte-by-byte)" in new Test {
        prep {
          """HTTP/1.1 200 Ok
            |Content-Length: 4
            |
            |ABCD"""
        }.toCharArray.map(_.toString).toSeq should rawMultiParseTo(HttpResponse(entity = "ABCD".getBytes))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }
    }

    "properly parse a chunked" - {
      val start =
        """HTTP/1.1 200 OK
          |Transfer-Encoding: chunked
          |Connection: lalelu
          |Content-Type: application/pdf
          |Server: spray-can
          |
          |"""
      val baseResponse = HttpResponse(headers = List(Server("spray-can"), Connection("lalelu")))

      "response start" in new Test {
        Seq(start, "rest") should generalMultiParseTo(
          Right(baseResponse.withEntity(Chunked(`application/pdf`, source()))),
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
            |10
            |0123456789""",
          """ABCDEF
            |0
            |
            |""") should generalMultiParseTo(
            Right(baseResponse.withEntity(Chunked(`application/pdf`, source(
              Chunk(ByteString("abc")),
              Chunk(ByteString("0123456789ABCDEF"), "some=stuff;bla"),
              Chunk(ByteString("0123456789ABCDEF"), "foo=bar"),
              Chunk(ByteString("0123456789ABCDEF")), LastChunk)))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "message end" in new Test {
        Seq(start,
          """0
            |
            |""") should generalMultiParseTo(
            Right(baseResponse.withEntity(Chunked(`application/pdf`, source(LastChunk)))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "message end with extension, trailer and remaining content" in new Test {
        Seq(start,
          """000;nice=true
            |Foo: pip
            | apo
            |Bar: xyz
            |
            |HT""") should generalMultiParseTo(
            Right(baseResponse.withEntity(Chunked(`application/pdf`,
              source(LastChunk("nice=true", List(RawHeader("Bar", "xyz"), RawHeader("Foo", "pip apo"))))))),
            Left(MessageStartError(400: StatusCode, ErrorInfo("Illegal HTTP message start"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }

      "response with additional transfer encodings" in new Test {
        Seq("""HTTP/1.1 200 OK
          |Transfer-Encoding: fancy, chunked
          |Cont""", """ent-Type: application/pdf
          |
          |""") should generalMultiParseTo(
          Right(HttpResponse(headers = List(`Transfer-Encoding`(TransferEncodings.Extension("fancy"))),
            entity = HttpEntity.Chunked(`application/pdf`, source()))),
          Left(EntityStreamError(ErrorInfo("Entity stream truncation"))))
        closeAfterResponseCompletion shouldEqual Seq(false)
      }
    }

    "reject a response with" - {
      "HTTP version 1.2" in new Test {
        Seq("HTTP/1.2 200 OK\r\n") should generalMultiParseTo(Left(MessageStartError(
          400: StatusCode, ErrorInfo("The server-side HTTP version is not supported"))))
      }

      "an illegal status code" in new Test {
        Seq("HTTP/1", ".1 2000 Something") should generalMultiParseTo(Left(MessageStartError(
          400: StatusCode, ErrorInfo("Illegal response status code"))))
      }

      "a too-long response status reason" in new Test {
        Seq("HTTP/1.1 204 12345678", "90123456789012\r\n") should generalMultiParseTo(Left(
          MessageStartError(400: StatusCode, ErrorInfo("Response reason phrase exceeds the configured limit of 21 characters"))))
      }
    }
  }

  override def afterAll() = system.shutdown()

  private class Test {
    var closeAfterResponseCompletion = Seq.empty[Boolean]

    class StrictEqualHttpResponse(val resp: HttpResponse) {
      override def equals(other: scala.Any): Boolean = other match {
        case other: StrictEqualHttpResponse ⇒
          this.resp.copy(entity = HttpEntity.Empty) == other.resp.copy(entity = HttpEntity.Empty) &&
            Await.result(this.resp.entity.toStrict(250.millis), 250.millis) ==
            Await.result(other.resp.entity.toStrict(250.millis), 250.millis)
      }

      override def toString = resp.toString
    }

    def strictEqualify[T](x: Either[T, HttpResponse]): Either[T, StrictEqualHttpResponse] =
      x.right.map(new StrictEqualHttpResponse(_))

    def parseTo(expected: HttpResponse*): Matcher[String] = parseTo(GET, expected: _*)
    def parseTo(requestMethod: HttpMethod, expected: HttpResponse*): Matcher[String] =
      multiParseTo(requestMethod, expected: _*).compose(_ :: Nil)

    def multiParseTo(expected: HttpResponse*): Matcher[Seq[String]] = multiParseTo(GET, expected: _*)
    def multiParseTo(requestMethod: HttpMethod, expected: HttpResponse*): Matcher[Seq[String]] =
      rawMultiParseTo(requestMethod, expected: _*).compose(_ map prep)

    def rawMultiParseTo(expected: HttpResponse*): Matcher[Seq[String]] = rawMultiParseTo(GET, expected: _*)
    def rawMultiParseTo(requestMethod: HttpMethod, expected: HttpResponse*): Matcher[Seq[String]] =
      generalRawMultiParseTo(requestMethod, expected.map(Right(_)): _*)

    def parseToError(error: ResponseOutput): Matcher[String] = generalMultiParseTo(Left(error)).compose(_ :: Nil)

    def generalMultiParseTo(expected: Either[ResponseOutput, HttpResponse]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(expected: _*).compose(_ map prep)

    def generalRawMultiParseTo(expected: Either[ResponseOutput, HttpResponse]*): Matcher[Seq[String]] =
      generalRawMultiParseTo(GET, expected: _*)
    def generalRawMultiParseTo(requestMethod: HttpMethod, expected: Either[ResponseOutput, HttpResponse]*): Matcher[Seq[String]] =
      equal(expected.map(strictEqualify))
        .matcher[Seq[Either[ResponseOutput, StrictEqualHttpResponse]]] compose { input: Seq[String] ⇒
          val future =
            Source(input.toList)
              .map(ByteString.apply)
              .section(name("parser"))(_.transform(() ⇒ newParser(requestMethod)))
              .splitWhen(x ⇒ x.isInstanceOf[MessageStart] || x.isInstanceOf[EntityStreamError])
              .headAndTail
              .collect {
                case (ResponseStart(statusCode, protocol, headers, createEntity, close), entityParts) ⇒
                  closeAfterResponseCompletion :+= close
                  Right(HttpResponse(statusCode, headers, createEntity(entityParts), protocol))
                case (x @ (MessageStartError(_, _) | EntityStreamError(_)), _) ⇒ Left(x)
              }.map { x ⇒
                Source {
                  x match {
                    case Right(response) ⇒ compactEntity(response.entity).fast.map(x ⇒ Right(response.withEntity(x)))
                    case Left(error)     ⇒ FastFuture.successful(Left(error))
                  }
                }
              }
              .flatten(FlattenStrategy.concat)
              .map(strictEqualify)
              .grouped(1000).runWith(Sink.head)
          Await.result(future, 500.millis)
        }

    def parserSettings: ParserSettings = ParserSettings(system)
    def newParser(requestMethod: HttpMethod = GET) = {
      val parser = new HttpResponseParser(parserSettings, HttpHeaderParser(parserSettings)(), () ⇒ requestMethod)
      parser
    }

    private def compactEntity(entity: ResponseEntity): Future[ResponseEntity] =
      entity match {
        case x: HttpEntity.Chunked ⇒ compactEntityChunks(x.chunks).fast.map(compacted ⇒ x.copy(chunks = compacted))
        case _                     ⇒ entity.toStrict(250.millis)
      }

    private def compactEntityChunks(data: Source[ChunkStreamPart]): Future[Source[ChunkStreamPart]] =
      data.grouped(1000).runWith(Sink.head)
        .fast.map(source(_: _*))
        .fast.recover { case _: NoSuchElementException ⇒ source() }

    def prep(response: String) = response.stripMarginWithNewline("\r\n")

    def source[T](elems: T*): Source[T] = Source(elems.toList)
  }
}
