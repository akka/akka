/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import org.scalatest.Inside
import org.scalatest.matchers.Matcher
import akka.util.ByteString
import akka.stream.scaladsl.{ Sink, Source }
import akka.http.impl.util._
import akka.http.scaladsl.model._
import akka.http.scaladsl.coding._
import headers._
import HttpEntity.{ ChunkStreamPart, Chunk }
import HttpCharsets._
import HttpEncodings._
import MediaTypes._
import StatusCodes._
import ContentTypes.`application/octet-stream`

import scala.concurrent.duration._

class CodingDirectivesSpec extends RoutingSpec with Inside {

  val echoRequestContent: Route = { ctx ⇒ ctx.complete(ctx.request.entity.dataBytes.utf8String) }

  val yeah = complete("Yeah!")
  lazy val yeahGzipped = compress("Yeah!", Gzip)
  lazy val yeahDeflated = compress("Yeah!", Deflate)

  lazy val helloGzipped = compress("Hello", Gzip)
  lazy val helloDeflated = compress("Hello", Deflate)

  val nope = complete((404, "Nope!"))
  lazy val nopeGzipped = compress("Nope!", Gzip)
  lazy val nopeDeflated = compress("Nope!", Deflate)

  "the NoEncoding decoder" should {
    "decode the request content if it has encoding 'identity'" in {
      Post("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeRequestWith(NoCoding) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "reject requests with content encoded with 'deflate'" in {
      Post("/", "yes") ~> `Content-Encoding`(deflate) ~> {
        decodeRequestWith(NoCoding) { echoRequestContent }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(identity) }
    }
    "decode the request content if no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequestWith(NoCoding) { echoRequestContent } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "leave request without content unchanged" in {
      Post() ~> decodeRequestWith(NoCoding) { completeOk } ~> check { response shouldEqual Ok }
    }

    val echoDecodedEntity =
      decodeRequestWith(NoCoding) {
        extractRequest { request ⇒
          complete(HttpResponse(200, entity = request.entity))
        }
      }
    "leave Strict request entity unchanged" in {
      val data = ByteString(Array.fill[Byte](10000)(42.toByte))
      val strictEntity = HttpEntity.Strict(`application/octet-stream`, data)

      Post("/", strictEntity) ~> echoDecodedEntity ~> check {
        responseEntity shouldEqual strictEntity
      }
    }
    "leave Default request entity unchanged" in {
      val chunks = Vector(ByteString("abc"), ByteString("def"), ByteString("ghi"))
      val data = Source(chunks)

      val defaultEntity = HttpEntity.Default(`application/octet-stream`, 9, data)

      Post("/", defaultEntity) ~> echoDecodedEntity ~> check {
        inside(responseEntity) {
          case HttpEntity.Default(`application/octet-stream`, 9, dataChunks) ⇒
            dataChunks.grouped(1000).runWith(Sink.head).awaitResult(1.second).toVector shouldEqual chunks
        }
      }
    }
    // CloseDelimited not support for requests
    "leave Chunked request entity unchanged" in {
      val chunks =
        Vector(ByteString("abc"), ByteString("def"), ByteString("ghi"))
          .map(ChunkStreamPart(_))
      val data = Source(chunks)

      val defaultEntity = HttpEntity.Chunked(`application/octet-stream`, data)

      Post("/", defaultEntity) ~> echoDecodedEntity ~> check {
        inside(responseEntity) {
          case HttpEntity.Chunked(`application/octet-stream`, dataChunks) ⇒
            dataChunks.grouped(1000).runWith(Sink.head).awaitResult(1.second).toVector shouldEqual chunks
        }
      }
    }
  }

  "the Gzip decoder" should {
    "decode the request content if it has encoding 'gzip'" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequestWith(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "reject the request content if it has encoding 'gzip' but is corrupt" in {
      Post("/", fromHexDump("000102")) ~> `Content-Encoding`(gzip) ~> {
        decodeRequestWith(Gzip) { echoRequestContent }
      } ~> check {
        status shouldEqual BadRequest
        responseAs[String] shouldEqual "The request's encoding is corrupt"
      }
    }
    "reject truncated gzip request content" in {
      Post("/", helloGzipped.dropRight(2)) ~> `Content-Encoding`(gzip) ~> {
        decodeRequestWith(Gzip) { echoRequestContent }
      } ~> check {
        status shouldEqual BadRequest
        responseAs[String] shouldEqual "The request's encoding is corrupt"
      }
    }
    "reject requests with content encoded with 'deflate'" in {
      Post("/", "Hello") ~> `Content-Encoding`(deflate) ~> {
        decodeRequestWith(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "reject requests without Content-Encoding header" in {
      Post("/", "Hello") ~> {
        decodeRequestWith(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "leave request without content unchanged" in {
      Post() ~> {
        decodeRequestWith(Gzip) { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "a (decodeRequestWith(Gzip) | decodeRequestWith(NoEncoding)) compound directive" should {
    lazy val decodeWithGzipOrNoEncoding = decodeRequestWith(Gzip) | decodeRequestWith(NoCoding)
    "decode the request content if it has encoding 'gzip'" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "decode the request content if it has encoding 'identity'" in {
      Post("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "decode the request content if no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeWithGzipOrNoEncoding { echoRequestContent } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "reject requests with content encoded with 'deflate'" in {
      Post("/", "yes") ~> `Content-Encoding`(deflate) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check {
        rejections shouldEqual Seq(
          UnsupportedRequestEncodingRejection(gzip),
          UnsupportedRequestEncodingRejection(identity))
      }
    }
  }

  "the encoder" should {
    "encode the response content with GZIP if the response is not a success and request has no Accept-Encoding header" in {
      Post() ~> {
        encodeResponseWith(Gzip) { nope }
      } ~> check { strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), nopeGzipped) }
    }
    "encode the response content with Deflate if the response is not a success and request has no Accept-Encoding header" in {
      Post() ~> {
        encodeResponseWith(Deflate) { nope }
      } ~> check { strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), nopeDeflated) }
    }
  }

  "the Gzip encoder" should {
    "encode the response content with GZIP if the client accepts it with a dedicated Accept-Encoding header" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "encode the response content with GZIP if the request has no Accept-Encoding header" in {
      Post() ~> {
        encodeResponseWith(Gzip) { yeah }
      } ~> check { strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped) }
    }
    "reject the request if the client does not accept GZIP encoding" in {
      Post() ~> `Accept-Encoding`(identity) ~> {
        encodeResponseWith(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnacceptedResponseEncodingRejection(gzip) }
    }
    "leave responses without content unchanged" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "leave responses with an already set Content-Encoding header unchanged" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) {
          RespondWithDirectives.respondWithHeader(`Content-Encoding`(identity)) { completeOk }
        }
      } ~> check { response shouldEqual Ok.withHeaders(`Content-Encoding`(identity)) }
    }
    "correctly encode the chunk stream produced by a chunked response" in {
      val text = "This is a somewhat lengthy text that is being chunked by the autochunk directive!"
      val textChunks =
        () ⇒ text.grouped(8).map { chars ⇒
          Chunk(chars.mkString): ChunkStreamPart
        }
      val chunkedTextEntity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source.fromIterator(textChunks))

      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) {
          complete(chunkedTextEntity)
        }
      } ~> check {
        response should haveContentEncoding(gzip)
        chunks.size shouldEqual (11 + 1) // 11 regular + the last one
        val bytes = chunks.foldLeft(ByteString.empty)(_ ++ _.data)
        Gzip.decode(bytes).awaitResult(1.second) should readAs(text)
      }
    }
    "correctly encode the chunk stream produced by an empty chunked response" in {
      val emptyChunkedEntity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source.empty)

      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) {
          complete(emptyChunkedEntity)
        }
      } ~> check {
        response should haveContentEncoding(gzip)
        val bytes = chunks.foldLeft(ByteString.empty)(_ ++ _.data)
        Gzip.decode(bytes).awaitResult(1.second) should readAs("")
      }
    }
  }

  "the encodeResponseWith(NoEncoding) directive" should {
    "produce a response if no Accept-Encoding is present in the request" in {
      Post() ~> encodeResponseWith(NoCoding) { completeOk } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a not encoded response if the client only accepts non matching encodings" in {
      Post() ~> `Accept-Encoding`(gzip, identity) ~> {
        encodeResponseWith(NoCoding) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }

      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Deflate, NoCoding) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "reject the request if the request has an 'Accept-Encoding: identity; q=0' header" in {
      Post() ~> `Accept-Encoding`(identity.withQValue(0f)) ~> {
        encodeResponseWith(NoCoding) { completeOk }
      } ~> check { rejection shouldEqual UnacceptedResponseEncodingRejection(identity) }
    }
  }

  "a (encodeResponse(Gzip) | encodeResponse(NoEncoding)) compound directive" should {
    lazy val encodeGzipOrIdentity = encodeResponseWith(Gzip) | encodeResponseWith(NoCoding)
    "produce a not encoded response if the request has no Accept-Encoding header" in {
      Post() ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate;q=0.5, gzip` header" in {
      Post() ~> `Accept-Encoding`(deflate.withQValue(.5f), gzip) ~> {
        encodeGzipOrIdentity { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a non-encoded response if the request has an `Accept-Encoding: identity` header" in {
      Post() ~> `Accept-Encoding`(identity) ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a non-encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post() ~> `Accept-Encoding`(deflate) ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
  }

  "the encodeResponse directive" should {
    "produce a non-encoded response if the request has no Accept-Encoding header" in {
      Get("/") ~> {
        encodeResponse { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: gzip, deflate` header" in {
      Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> {
        encodeResponse { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponse { yeah }
      } ~> check {
        response should haveContentEncoding(deflate)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
  }

  "the encodeResponseWith directive" should {
    "produce a response encoded with the specified Encoder if the request has a matching Accept-Encoding header" in {
      Get("/") ~> `Accept-Encoding`(gzip) ~> {
        encodeResponseWith(Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a response encoded with one of the specified Encoders if the request has a matching Accept-Encoding header" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponseWith(Gzip, Deflate) { yeah }
      } ~> check {
        response should haveContentEncoding(deflate)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce a response encoded with the first of the specified Encoders if the request has no Accept-Encoding header" in {
      Get("/") ~> {
        encodeResponseWith(Gzip, Deflate) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a response with no encoding if the request has an empty Accept-Encoding header" in {
      Get("/") ~> `Accept-Encoding`() ~> {
        encodeResponseWith(Gzip, Deflate, NoCoding) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "negotiate the correct content encoding" in {
      Get("/") ~> `Accept-Encoding`(identity.withQValue(.5f), deflate.withQValue(0f), gzip) ~> {
        encodeResponseWith(NoCoding, Deflate, Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }

      Get("/") ~> `Accept-Encoding`(HttpEncodingRange.`*`, deflate withQValue 0.2) ~> {
        encodeResponseWith(Deflate, Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "reject the request if it has an Accept-Encoding header with an encoding that doesn't match" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponseWith(Gzip) { yeah }
      } ~> check {
        rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
      }
    }
    "reject the request if it has an Accept-Encoding header with an encoding that matches but is blacklisted" in {
      Get("/") ~> `Accept-Encoding`(gzip.withQValue(0f)) ~> {
        encodeResponseWith(Gzip) { yeah }
      } ~> check {
        rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
      }
    }
  }

  "the decodeRequest directive" should {
    "decode the request content if it has a `Content-Encoding: gzip` header and the content is gzip encoded" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "decode the request content if it has a `Content-Encoding: deflate` header and the content is deflate encoded" in {
      Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> {
        decodeRequest { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "decode the request content if it has a `Content-Encoding: identity` header and the content is not encoded" in {
      Post("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeRequest { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "decode the request content using NoEncoding if no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequest { echoRequestContent } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "reject the request if it has a `Content-Encoding: deflate` header but the request is encoded with Gzip" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(deflate) ~>
        decodeRequest { echoRequestContent } ~> check {
          status shouldEqual BadRequest
          responseAs[String] shouldEqual "The request's encoding is corrupt"
        }
    }
  }

  "the decodeRequestWith directive" should {
    "decode the request content if its `Content-Encoding` header matches the specified encoder" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequestWith(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "reject the request if its `Content-Encoding` header doesn't match the specified encoder" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(deflate) ~> {
        decodeRequestWith(Gzip) { echoRequestContent }
      } ~> check {
        rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
    "reject the request when decodeing with GZIP and no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequestWith(Gzip) { echoRequestContent } ~> check {
        rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
  }

  "the (decodeRequest & encodeResponse) compound directive" should {
    lazy val decodeEncode = decodeRequest & encodeResponse
    "decode a GZIP encoded request and produce a none encoded response if the request has no Accept-Encoding header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveNoContentEncoding
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), "Hello")
      }
    }
    "decode a GZIP encoded request and produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> `Accept-Encoding`(deflate) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(deflate)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloDeflated)
      }
    }
    "decode an unencoded request and produce a GZIP encoded response if the request has an `Accept-Encoding: gzip` header" in {
      Post("/", "Hello") ~> `Accept-Encoding`(gzip) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(gzip)
        strictify(responseEntity) shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
  }

  def compress(input: String, encoder: Encoder): ByteString = {
    val compressor = encoder.newCompressor
    compressor.compressAndFlush(ByteString(input)) ++ compressor.finish()
  }

  def hexDump(bytes: Array[Byte]) = bytes.map("%02x" format _).mkString
  def fromHexDump(dump: String) = dump.grouped(2).toArray.map(chars ⇒ Integer.parseInt(new String(chars), 16).toByte)

  def haveNoContentEncoding: Matcher[HttpResponse] = be(None) compose { (_: HttpResponse).header[`Content-Encoding`] }
  def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(Some(`Content-Encoding`(encoding))) compose { (_: HttpResponse).header[`Content-Encoding`] }

  def readAs(string: String, charset: String = "UTF8") = be(string) compose { (_: ByteString).decodeString(charset) }

  def strictify(entity: HttpEntity) = entity.toStrict(1.second).awaitResult(1.second)
}
