/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.routing._
import akka.http.util._
import akka.http.encoding._
import akka.http.model._
import akka.http.unmarshalling._
import headers._
import HttpCharsets._
import HttpEncodings._
import MediaTypes._
import org.scalatest.matchers.Matcher

class EncodingDirectivesSpec extends RoutingSpec {

  val echoRequestContent: Route = { ctx ⇒ ctx.complete(ctx.request.entity.asString) }

  val yeah = complete("Yeah!")
  val yeahGzipped = compress("Yeah!", Gzip)
  val yeahDeflated = compress("Yeah!", Deflate)

  val helloGzipped = compress("Hello", Gzip)
  val helloDeflated = compress("Hello", Deflate)

  "the NoEncoding decoder" should {
    "decode the request content if it has encoding 'identity'" in {
      Get("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeRequest(NoEncoding) { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "yes" }
    }
    "reject requests with content encoded with 'deflate'" in {
      Get("/", "yes") ~> `Content-Encoding`(deflate) ~> {
        decodeRequest(NoEncoding) { echoRequestContent }
      } ~> check { rejection mustEqual UnsupportedRequestEncodingRejection(identity) }
    }
    "decode the request content if no Content-Encoding header is present" in {
      Get("/", "yes") ~> decodeRequest(NoEncoding) { echoRequestContent } ~> check { responseAs[String] mustEqual "yes" }
    }
    "leave request without content unchanged" in {
      Get() ~> decodeRequest(Gzip) { completeOk } ~> check { response mustEqual Ok }
    }
  }

  "the Gzip decoder" should {
    "decode the request content if it has encoding 'gzip'" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "Hello" }
    }
    "reject the request content if it has encoding 'gzip' but is corrupt" in {
      Get("/", fromHexDump("000102")) ~> `Content-Encoding`(gzip) ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { rejection mustEqual CorruptRequestEncodingRejection("Not in GZIP format") }
    }
    "reject requests with content encoded with 'deflate'" in {
      Get("/", "Hello") ~> `Content-Encoding`(deflate) ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { rejection mustEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "reject requests without Content-Encoding header" in {
      Get("/", "Hello") ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { rejection mustEqual UnsupportedRequestEncodingRejection(gzip) }
    }
    "leave request without content unchanged" in {
      Get() ~> {
        decodeRequest(Gzip) { completeOk }
      } ~> check { response mustEqual Ok }
    }
  }

  "a (decodeRequest(Gzip) | decodeRequest(NoEncoding)) compound directive" should {
    val decodeWithGzipOrNoEncoding = (decodeRequest(Gzip) | decodeRequest(NoEncoding))
    "decode the request content if it has encoding 'gzip'" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "Hello" }
    }
    "decode the request content if it has encoding 'identity'" in {
      Get("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "yes" }
    }
    "decode the request content if no Content-Encoding header is present" in {
      Get("/", "yes") ~> decodeWithGzipOrNoEncoding { echoRequestContent } ~> check { responseAs[String] mustEqual "yes" }
    }
    "reject requests with content encoded with 'deflate'" in {
      Get("/", "yes") ~> `Content-Encoding`(deflate) ~> {
        decodeWithGzipOrNoEncoding { echoRequestContent }
      } ~> check {
        rejections mustEqual Seq(
          UnsupportedRequestEncodingRejection(gzip),
          UnsupportedRequestEncodingRejection(identity))
      }
    }
  }

  "the Gzip encoder" should {
    "encode the response content with GZIP if the client accepts it with a dedicated Accept-Encoding header" in {
      Get() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "encode the response content with GZIP if the request has no Accept-Encoding header" in {
      Get() ~> {
        encodeResponse(Gzip) { yeah }
      } ~> check { body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped) }
    }
    "reject the request if the client does not accept GZIP encoding" in {
      Get() ~> `Accept-Encoding`(identity) ~> {
        encodeResponse(Gzip) { completeOk }
      } ~> check { rejection mustEqual UnacceptedResponseEncodingRejection(gzip) }
    }
    "leave responses without content unchanged" in {
      Get() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "leave responses with an already set Content-Encoding header unchanged" in {
      Get() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) {
          respondWithHeader(`Content-Encoding`(identity)) { yeah }
        }
      } ~> check { responseAs[String] mustEqual "Yeah!" }
    }
    "correctly encode the chunk stream produced by a chunked response" in pending /*{
      val text = "This is a somewhat lengthy text that is being chunked by the autochunk directive!"
      Get() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(Gzip) {
          autoChunk(8) {
            complete(text)
          }
        }
      } ~> check {
        response must haveContentEncoding(gzip)
        chunks must haveSize(11)
        val bytes = chunks.foldLeft(body.data.toByteArray)(_ ++ _.data.toByteArray)
        Gzip.newDecompressor.decompress(bytes) must readAs(text)
      }
    }*/
  }

  "the encodeResponse(NoEncoding) directive" should {
    "produce a response if no Accept-Encoding is present in the request" in {
      Get() ~> encodeResponse(NoEncoding) { completeOk } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "produce a response if the client explicitly accepts non-encoded responses" in {
      Get() ~> `Accept-Encoding`(gzip, identity) ~> {
        encodeResponse(NoEncoding) { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "reject the request if the client does not accept `identity` encoding" in {
      Get() ~> `Accept-Encoding`(gzip) ~> {
        encodeResponse(NoEncoding) { completeOk }
      } ~> check { rejection mustEqual UnacceptedResponseEncodingRejection(identity) }
    }
    "reject the request if the request has an 'Accept-Encoding: identity; q=0' header" in {
      pending
    }
  }

  "a (encodeResponse(Gzip) | encodeResponse(NoEncoding)) compound directive" should {
    val encodeGzipOrIdentity = (encodeResponse(Gzip) | encodeResponse(NoEncoding))
    "produce a GZIP encoded response if the request has no Accept-Encoding header" in {
      Get() ~> {
        encodeGzipOrIdentity { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Get() ~> `Accept-Encoding`(deflate, gzip) ~> {
        encodeGzipOrIdentity { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a non-encoded response if the request has an `Accept-Encoding: identity` header" in {
      Get() ~> `Accept-Encoding`(identity) ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "reject the request if it has an `Accept-Encoding: deflate` header" in {
      Get() ~> `Accept-Encoding`(deflate) ~> {
        encodeGzipOrIdentity { completeOk }
      } ~> check {
        rejections mustEqual Seq(
          UnacceptedResponseEncodingRejection(gzip),
          UnacceptedResponseEncodingRejection(identity))
      }
    }
  }

  "a (encodeResponse(NoEncoding) | encodeResponse(Gzip)) compound directive" should {
    val encodeIdentityOrGzip = (encodeResponse(NoEncoding) | encodeResponse(Gzip))
    "produce a non-encoded encoded response if the request has no Accept-Encoding header" in {
      Get() ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "produce a non-encoded response if the request has an `Accept-Encoding: identity` header" in {
      Get() ~> `Accept-Encoding`(identity) ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
    "produce a GZIP encoded response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Get() ~> `Accept-Encoding`(deflate, gzip) ~> {
        encodeIdentityOrGzip { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "reject the request if it has an `Accept-Encoding: deflate` header" in {
      Get() ~> `Accept-Encoding`(deflate) ~> {
        encodeIdentityOrGzip { completeOk }
      } ~> check {
        rejections mustEqual Seq(
          UnacceptedResponseEncodingRejection(identity),
          UnacceptedResponseEncodingRejection(gzip))
      }
    }
  }

  //# compressResponse-example
  "the compressResponse directive" should {
    "produce a GZIP compressed response if the request has no Accept-Encoding header" in {
      Get("/") ~> {
        compressResponse() { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a GZIP compressed response if the request has an `Accept-Encoding: gzip, deflate` header" in {
      Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> {
        compressResponse() { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a Deflate compressed response if the request has an `Accept-Encoding: deflate` header" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        compressResponse() { yeah }
      } ~> check {
        response must haveContentEncoding(deflate)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce an uncompressed response if the request has an `Accept-Encoding: identity` header" in {
      Get("/") ~> `Accept-Encoding`(identity) ~> {
        compressResponse() { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
  }
  //#

  //# compressResponseIfRequested-example
  "the compressResponseIfRequested directive" should {
    "produce an uncompressed response if the request has no Accept-Encoding header" in {
      Get("/") ~> {
        compressResponseIfRequested() { yeah }
      } ~> check {
        response must haveNoContentEncoding
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), "Yeah!")
      }
    }
    "produce a GZIP compressed response if the request has an `Accept-Encoding: deflate, gzip` header" in {
      Get("/") ~> `Accept-Encoding`(deflate, gzip) ~> {
        compressResponseIfRequested() { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        compressResponseIfRequested() { yeah }
      } ~> check {
        response must haveContentEncoding(deflate)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce an uncompressed response if the request has an `Accept-Encoding: identity` header" in {
      Get("/") ~> `Accept-Encoding`(identity) ~> {
        compressResponseIfRequested() { completeOk }
      } ~> check {
        response mustEqual Ok
        response must haveNoContentEncoding
      }
    }
  }
  //#

  //# compressResponseWith-example
  "the compressResponseWith directive" should {
    "produce a response compressed with the specified Encoder if the request has a matching Accept-Encoding header" in {
      Get("/") ~> `Accept-Encoding`(gzip) ~> {
        compressResponse(Gzip) { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "produce a response compressed with one of the specified Encoders if the request has a matching Accept-Encoding header" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        compressResponse(Gzip, Deflate) { yeah }
      } ~> check {
        response must haveContentEncoding(deflate)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahDeflated)
      }
    }
    "produce a response compressed with the first of the specified Encoders if the request has no Accept-Encoding header" in {
      Get("/") ~> {
        compressResponse(Gzip, Deflate) { yeah }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), yeahGzipped)
      }
    }
    "reject the request if it has an Accept-Encoding header with an encoding that doesn't match" in {
      Get("/") ~> `Accept-Encoding`(deflate) ~> {
        compressResponse(Gzip) { yeah }
      } ~> check {
        rejection mustEqual UnacceptedResponseEncodingRejection(gzip)
      }
    }
  }
  //#

  //# decompressRequest-example
  "the decompressRequest directive" should {
    "decompress the request content if it has a `Content-Encoding: gzip` header and the content is gzip encoded" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decompressRequest() { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "Hello" }
    }
    "decompress the request content if it has a `Content-Encoding: deflate` header and the content is deflate encoded" in {
      Get("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> {
        decompressRequest() { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "Hello" }
    }
    "decompress the request content if it has a `Content-Encoding: identity` header and the content is not encoded" in {
      Get("/", "yes") ~> `Content-Encoding`(identity) ~> {
        decompressRequest() { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "yes" }
    }
    "decompress the request content using NoEncoding if no Content-Encoding header is present" in {
      Get("/", "yes") ~> decompressRequest() { echoRequestContent } ~> check { responseAs[String] mustEqual "yes" }
    }
    "reject the request if it has a `Content-Encoding: deflate` header but the request is compressed with Gzip" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(deflate) ~> {
        decompressRequest() { echoRequestContent }
      } ~> check {
        rejections(0) mustEqual UnsupportedRequestEncodingRejection(gzip)
        rejections(1) mustBe a[CorruptRequestEncodingRejection]
        rejections(2) mustEqual UnsupportedRequestEncodingRejection(identity)
      }
    }
  }
  //#

  //# decompressRequestWith-example
  "the decompressRequestWith directive" should {
    "decompress the request content if its `Content-Encoding` header matches the specified encoder" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decompressRequest(Gzip) { echoRequestContent }
      } ~> check { responseAs[String] mustEqual "Hello" }
    }
    "reject the request if its `Content-Encoding` header doesn't match the specified encoder" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(deflate) ~> {
        decompressRequest(Gzip) { echoRequestContent }
      } ~> check {
        rejection mustEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
    "reject the request when decompressing with GZIP and no Content-Encoding header is present" in {
      Get("/", "yes") ~> decompressRequest(Gzip) { echoRequestContent } ~> check {
        rejection mustEqual UnsupportedRequestEncodingRejection(gzip)
      }
    }
  }
  //#

  //# decompress-compress-combination-example
  "the (decompressRequest & compressResponse) compound directive" should {
    val decompressCompress = (decompressRequest() & compressResponse())
    "decompress a GZIP compressed request and produce a GZIP compressed response if the request has no Accept-Encoding header" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decompressCompress { echoRequestContent }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
    "decompress a GZIP compressed request and produce a Deflate compressed response if the request has an `Accept-Encoding: deflate` header" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> `Accept-Encoding`(deflate) ~> {
        decompressCompress { echoRequestContent }
      } ~> check {
        response must haveContentEncoding(deflate)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloDeflated)
      }
    }
    "decompress an uncompressed request and produce a GZIP compressed response if the request has an `Accept-Encoding: gzip` header" in {
      Get("/", "Hello") ~> `Accept-Encoding`(gzip) ~> {
        decompressCompress { echoRequestContent }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
  }
  //#

  "the (decompressRequest & compressResponseIfRequested) compound directive" should {
    val decompressCompressIfRequested = (decompressRequest() & compressResponseIfRequested())
    "decode a GZIP encoded request and produce a non-encoded response if the request has no Accept-Encoding header" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> {
        decompressCompressIfRequested { echoRequestContent }
      } ~> check {
        responseAs[String] mustEqual "Hello"
      }
    }
    "decode a GZIP encoded request and produce a Deflate encoded response if the request has an `Accept-Encoding: deflate` header" in {
      Get("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> `Accept-Encoding`(deflate) ~> {
        decompressCompressIfRequested { echoRequestContent }
      } ~> check {
        response must haveContentEncoding(deflate)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloDeflated)
      }
    }
    "decode a non-encoded request and produce a GZIP encoded response if the request has an `Accept-Encoding: gzip` header" in {
      Get("/", "Hello") ~> `Accept-Encoding`(gzip) ~> {
        decompressCompressIfRequested { echoRequestContent }
      } ~> check {
        response must haveContentEncoding(gzip)
        body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), helloGzipped)
      }
    }
  }

  def compress(input: String, encoder: Encoder) = encoder.newCompressor.compress(input.getBytes).finish

  def hexDump(bytes: Array[Byte]) = bytes.map("%02x" format _).mkString
  def fromHexDump(dump: String) = dump.grouped(2).toArray.map(chars ⇒ Integer.parseInt(new String(chars), 16).toByte)

  def haveNoContentEncoding: Matcher[HttpResponse] = be(None) compose { (_: HttpResponse).header[`Content-Encoding`] }
  def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(Some(`Content-Encoding`(encoding))) compose { (_: HttpResponse).header[`Content-Encoding`] }

  def readAs(string: String, charset: String = "UTF8") = be(string) compose { new String(_: Array[Byte], charset) }
}
