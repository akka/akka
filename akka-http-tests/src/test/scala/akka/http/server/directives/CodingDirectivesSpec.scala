/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import org.scalatest.matchers.Matcher
import akka.util.ByteString
import akka.stream.scaladsl.Source
import akka.http.util._
import akka.http.model._
import akka.http.coding._
import headers._
import HttpEntity.{ ChunkStreamPart, Chunk }
import HttpCharsets._
import HttpEncodings._
import MediaTypes._
import StatusCodes._

class CodingDirectivesSpec extends RoutingSpec {

  val echoRequestContent: Route = { ctx ⇒ ctx.complete(ctx.request.entity.dataBytes.utf8String) }

  val yeah = complete("Yeah!")
  lazy val yeahGzipped = compress("Yeah!", Gzip)
  lazy val yeahDeflated = compress("Yeah!", Deflate)

  lazy val helloGzipped = compress("Hello", Gzip)
  lazy val helloDeflated = compress("Hello", Deflate)

  "the NoEncoding decoder" should {
    "decode the request content if it has encoding 'identity'" in {
      Post("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeRequest(NoCoding) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "reject requests with content encoded with 'deflate'" in {
      Post("/", "yes") ~> `Content-Encoding`(deflate) ~> {
        decodeRequest(NoCoding) { echoRequestContent }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(identity) }
    }
    "decode the request content if no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequest(NoCoding) { echoRequestContent } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "leave request without content unchanged" in {
      Post() ~> decodeRequest(Gzip) { completeOk } ~> check { response shouldEqual Ok }
    }
  }

  "the Gzip decoder" should {
    "decode the request content if it has encoding 'gzip'" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "reject the request content if it has encoding 'gzip' but is corrupt" in {
      Post("/", fromHexDump("000102")) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check {
        status shouldEqual BadRequest
        responseAs[String] shouldEqual "The request's encoding is corrupt"
      }
    }
    "reject truncated gzip request content" in {
      Post("/", helloGzipped.dropRight(2)) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check {
        status shouldEqual BadRequest
        responseAs[String] shouldEqual "The request's encoding is corrupt"
      }
    }
    "reject requests with content encoded with 'deflate'" in {
      Post("/", "Hello") ~> `Content-Encoding`(deflate) ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "reject requests without Content-Encoding header" in {
      Post("/", "Hello") ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "leave request without content unchanged" in {
      Post() ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { response shouldEqual Ok }
    }
  }

  "a (decodeRequest(Gzip) | decodeRequest(NoEncoding)) compound directive" should {
    lazy val decodeWithGzipOrNoEncoding = decodeRequest(Gzip) | decodeRequest(NoCoding)
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

  "the Gzip encoder" should {
    "encode the response content with GZIP if the client accepts it with a dedicated Accept-Encoding header" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "encode the response content with GZIP if the request has no Accept-Encoding header" in {
      Post() ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check { responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped) }
    }
    "reject the request if the client does not accept GZIP encoding" in {
      Post() ~> `Accept-Encoding`(identity) ~> {
        encodeResponse(Gzip) { completeOk }
      } ~> check { rejection shouldEqual UnacceptedResponseEncodingRejection(gzip) }
    }
    "leave responses without content unchanged" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "leave responses with an already set Content-Encoding header unchanged" in {
      pending

      // FIXME: when RespondWithDirectives have been imported
      /*Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) {
          respondWithHeader(`Content-Encoding`(identity)) { yeah }
        }
      } ~> check { responseAs[String] shouldEqual "Yeah!" }*/
    }
    "correctly encode the chunk stream produced by a chunked response" in {
      val text = "This is a somewhat lengthy text that is being chunked by the autochunk directive!"
      val textChunks =
        () ⇒ text.grouped(8).map { chars ⇒
          Chunk(chars.mkString): ChunkStreamPart
        }
      val chunkedTextEntity = HttpEntity.Chunked(MediaTypes.`text/plain`, Source(textChunks))

      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) {
          complete(chunkedTextEntity)
        }
      } ~> check {
        response should haveContentEncoding(gzip)
        chunks.size shouldEqual (11 + 1) // 11 regular + the last one
        val bytes = chunks.foldLeft(ByteString.empty)(_ ++ _.data)
        Gzip.decode(bytes) should readAs(text)
      }
    }
  }

  "the encodeResponse(NoEncoding) directive" should {
    "produce a response if no Accept-Encoding is present in the request" in {
      Post() ~> encodeResponse(NoCoding) { completeOk } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a response if the client explicitly accepts non-encoded responses" in {
      Post() ~> `Accept-Encoding`(gzip, identity) ~> {
        encodeResponse(NoCoding) { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "reject the request if the client does not accept `identity` encoding" in {
      Post() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(NoCoding) { completeOk }
      } ~> check { rejection shouldEqual UnacceptedResponseEncodingRejection(identity) }
    }
    "reject the request if the request has an 'Accept-Encoding: identity; q=0' header" in {
      pending
    }
  }

  "a (encodeResponse(Gzip) | encodeResponse(NoEncoding)) compound directive" should {
    lazy val encodeGzipOrIdentity = encodeResponse(Gzip) | encodeResponse(NoCoding)
    "produce a GZIP encoded response if the request has no Accept-Encoding header" in {
      Post() ~> {
        encodeGzipOrIdentity { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Post() ~> `Accept-Encoding`(deflate, gzip) ~> {
        encodeGzipOrIdentity { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
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
    "reject the request if it has an `Accept-Encoding: deflate` header" in {
      Post() ~> `Accept-Encoding`(deflate) ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        rejections shouldEqual Seq(
          UnacceptedResponseEncodingRejection(gzip),
          UnacceptedResponseEncodingRejection(identity))
      }
    }
  }

  "a (encodeResponse(NoEncoding) | encodeResponse(Gzip)) compound directive" should {
    lazy val encodeIdentityOrGzip = encodeResponse(NoCoding) | encodeResponse(Gzip)
    "produce a non-encoded encoded response if the request has no Accept-Encoding header" in {
      Post() ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a non-encoded response if the request has an `Accept-Encoding: identity` header" in {
      Post() ~> `Accept-Encoding`(identity) ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Post() ~> `Accept-Encoding`(deflate, gzip) ~> {
        encodeIdentityOrGzip { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "reject the request if it has an `Accept-Encoding: deflate` header" in {
      Post() ~> `Accept-Encoding`(deflate) ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        rejections shouldEqual Seq(
          UnacceptedResponseEncodingRejection(identity),
          UnacceptedResponseEncodingRejection(gzip))
      }
    }
  }

  "the encodeResponse directive" should {
    "produce a GZIP encoded response if the request has no Accept-Encoding header" in {
      Post("/") ~> {
        encodeResponse() { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: gzip, deflate` header" in {
      Post("/") ~> `Accept-Encoding`(gzip, deflate) ~> {
        encodeResponse() { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponse() { yeah }
      } ~> check {
        response should haveContentEncoding(deflate)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce an unencoded response if the request has an `Accept-Encoding: identity` header" in {
      Post("/") ~> `Accept-Encoding`(identity) ~> {
        encodeResponse() { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
  }

  "the encodeResponseIfRequested directive" should {
    "produce an unencoded response if the request has no Accept-Encoding header" in {
      Post("/") ~> {
        encodeResponseIfRequested() { yeah }
      } ~> check {
        response should haveNoContentEncoding
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), "Yeah!")
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Post("/") ~> `Accept-Encoding`(deflate, gzip) ~> {
        encodeResponseIfRequested() { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponseIfRequested() { yeah }
      } ~> check {
        response should haveContentEncoding(deflate)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce an unencoded response if the request has an `Accept-Encoding: identity` header" in {
      Post("/") ~> `Accept-Encoding`(identity) ~> {
        encodeResponseIfRequested() { completeOk }
      } ~> check {
        response shouldEqual Ok
        response should haveNoContentEncoding
      }
    }
  }

  "the encodeResponse directive" should {
    "produce a response encoded with the specified Encoder if the request has a matching Accept-Encoding header" in {
      Post("/") ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a response encoded with one of the specified Encoders if the request has a matching Accept-Encoding header" in {
      Post("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponse(Gzip, Deflate) { yeah }
      } ~> check {
        response should haveContentEncoding(deflate)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce a response encoded with the first of the specified Encoders if the request has no Accept-Encoding header" in {
      Post("/") ~> {
        encodeResponse(Gzip, Deflate) { yeah }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "reject the request if it has an Accept-Encoding header with an encoding that doesn't match" in {
      Post("/") ~> `Accept-Encoding`(deflate) ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check {
        rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
      }
    }
  }

  "the decodeRequest directive" should {
    "decode the request content if it has a `Content-Encoding: gzip` header and the content is gzip encoded" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest() { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "decode the request content if it has a `Content-Encoding: deflate` header and the content is deflate encoded" in {
      Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> {
        decodeRequest() { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "decode the request content if it has a `Content-Encoding: identity` header and the content is not encoded" in {
      Post("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeRequest() { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "decode the request content using NoEncoding if no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequest() { echoRequestContent } ~> check { responseAs[String] shouldEqual "yes" }
    }
    "reject the request if it has a `Content-Encoding: deflate` header but the request is encoded with Gzip" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(deflate) ~>
        decodeRequest() { echoRequestContent } ~> check {
          status shouldEqual BadRequest
          responseAs[String] shouldEqual "The request's encoding is corrupt"
        }
    }
  }

  "the decodeRequestWith directive" should {
    "decode the request content if its `Content-Encoding` header matches the specified encoder" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] shouldEqual "Hello" }
    }
    "reject the request if its `Content-Encoding` header doesn't match the specified encoder" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(deflate) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check {
        rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
    "reject the request when decodeing with GZIP and no Content-Encoding header is present" in {
      Post("/", "yes") ~> decodeRequest(Gzip) { echoRequestContent } ~> check {
        rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
  }

  "the (decodeRequest & encodeResponse) compound directive" should {
    lazy val decodeEncode = decodeRequest() & encodeResponse()
    "decode a GZIP encoded request and produce a GZIP encoded response if the request has no Accept-Encoding header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
    "decode a GZIP encoded request and produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> `Accept-Encoding`(deflate) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(deflate)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloDeflated)
      }
    }
    "decode an unencoded request and produce a GZIP encoded response if the request has an `Accept-Encoding: gzip` header" in {
      Post("/", "Hello") ~> `Accept-Encoding`(gzip) ~> {
        decodeEncode { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
  }

  "the (decodeRequest & encodeResponseIfRequested) compound directive" should {
    lazy val decodeEncodeIfRequested = decodeRequest() & encodeResponseIfRequested()
    "decode a GZIP encoded request and produce a non-encoded response if the request has no Accept-Encoding header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeEncodeIfRequested { echoRequestContent }
      } ~> check {
        responseAs[String] shouldEqual "Hello"
      }
    }
    "decode a GZIP encoded request and produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> `Accept-Encoding`(deflate) ~> {
        decodeEncodeIfRequested { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(deflate)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloDeflated)
      }
    }
    "decode a non-encoded request and produce a GZIP encoded response if the request has an `Accept-Encoding: gzip` header" in {
      Post("/", "Hello") ~> `Accept-Encoding`(gzip) ~> {
        decodeEncodeIfRequested { echoRequestContent }
      } ~> check {
        response should haveContentEncoding(gzip)
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
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
}
