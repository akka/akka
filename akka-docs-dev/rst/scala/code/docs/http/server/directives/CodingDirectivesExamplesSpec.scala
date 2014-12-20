/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.server
package directives

import akka.http.coding._
import akka.http.model.{ HttpResponse, StatusCodes }
import akka.http.model.headers.{ HttpEncodings, HttpEncoding, `Accept-Encoding`, `Content-Encoding` }
import akka.http.model.headers.HttpEncodings._
import akka.http.server._
import akka.util.ByteString
import org.scalatest.matchers.Matcher

class CodingDirectivesExamplesSpec extends RoutingSpec {
  "compressResponse-0" in {
    val route = compressResponse() { complete("content") }

    Get("/") ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      response should haveContentEncoding(deflate)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      response should haveContentEncoding(identity)
      responseAs[String] shouldEqual "content"
    }
  }
  "compressResponse-1" in {
    val route = compressResponse(Gzip) { complete("content") }

    Get("/") ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
  }
  "compressResponseIfRequested" in {
    val route = compressResponseIfRequested() { complete("content") }

    Get("/") ~> route ~> check {
      response should haveContentEncoding(identity)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      response should haveContentEncoding(deflate)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      response should haveContentEncoding(identity)
    }
  }
  "encodeResponse" in {
    val route = encodeResponse(Gzip) { complete("content") }

    Get("/") ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(gzip, deflate) ~> route ~> check {
      response should haveContentEncoding(gzip)
    }
    Get("/") ~> `Accept-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
    Get("/") ~> `Accept-Encoding`(identity) ~> route ~> check {
      rejection shouldEqual UnacceptedResponseEncodingRejection(gzip)
    }
  }

  val helloGzipped = compress("Hello", Gzip)
  val helloDeflated = compress("Hello", Deflate)
  "decodeRequest" in {
    val route =
      decodeRequest(Gzip) {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
    }
    Post("/", "hello") ~> `Content-Encoding`(identity) ~> route ~> check {
      rejection shouldEqual UnsupportedRequestEncodingRejection(gzip)
    }
  }
  "decompressRequest-0" in {
    val route =
      decompressRequest() {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", "hello uncompressed") ~> `Content-Encoding`(identity) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'hello uncompressed'"
    }
  }
  "decompressRequest-1" in {
    val route =
      decompressRequest(Gzip, NoCoding) {
        entity(as[String]) { content: String =>
          complete(s"Request content: '$content'")
        }
      }

    Post("/", helloGzipped) ~> `Content-Encoding`(gzip) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'Hello'"
    }
    Post("/", helloDeflated) ~> `Content-Encoding`(deflate) ~> route ~> check {
      rejections shouldEqual List(UnsupportedRequestEncodingRejection(gzip), UnsupportedRequestEncodingRejection(identity))
    }
    Post("/", "hello uncompressed") ~> `Content-Encoding`(identity) ~> route ~> check {
      responseAs[String] shouldEqual "Request content: 'hello uncompressed'"
    }
  }

  def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(encoding) compose { (_: HttpResponse).header[`Content-Encoding`].map(_.encodings.head).getOrElse(HttpEncodings.identity) }

  def compress(input: String, encoder: Encoder): ByteString = encoder.encode(ByteString(input))
}
