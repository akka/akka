/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.util.ByteString
import headers._
import org.scalatest.{ Matchers, WordSpec }

class HttpMessageSpec extends WordSpec with Matchers {

  def test(uri: String, effectiveUri: String, headers: HttpHeader*) =
    HttpRequest.effectiveUri(Uri(uri), List(headers: _*), securedConnection = false, null) shouldEqual Uri(effectiveUri)

  def fail(uri: String, hostHeader: Host) =
    an[IllegalUriException] should be thrownBy
      HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null)

  def failWithNoHostHeader(hostHeader: Option[Host], details: String) = {
    val thrown = the[IllegalUriException] thrownBy
      HttpRequest.effectiveUri(Uri("/relative"), hostHeader.toList, securedConnection = false, Host(""))

    thrown should have message
      s"Cannot establish effective URI of request to `/relative`, request has a relative URI and $details: " +
      "consider setting `akka.http.server.default-host-header`"
  }

  "HttpRequest" should {
    "provide an effective URI for relative URIs or matching Host-headers" in {
      test("/segment", "http://example.com/segment", Host("example.com"))
      test("http://example.com/", "http://example.com/", Host("example.com"))
      test("http://example.com:8080/", "http://example.com:8080/", Host("example.com", 8080))
      test("/websocket", "ws://example.com/websocket", Host("example.com"), Upgrade(List(UpgradeProtocol("websocket"))))
    }

    "throw IllegalUriException for non-matching Host-headers" in {
      fail("http://example.net/", Host("example.com"))
      fail("http://example.com:8080/", Host("example.com"))
      fail("http://example.com/", Host("example.com", 8080))
    }

    "throw IllegalUriException for relative URI with no default Host header" in {
      failWithNoHostHeader(None, "is missing a `Host` header")
    }

    "throw IllegalUriException for relative URI with empty Host header and no default Host header" in {
      failWithNoHostHeader(Some(Host("")), "an empty `Host` header")
    }

    "throw IllegalUriException for an invalid URI schema" in {
      an[IllegalUriException] should be thrownBy
        HttpRequest(uri = Uri("htp://example.com"))
    }

    "throw IllegalUriException for empty URI" in {
      an[IllegalUriException] should be thrownBy
        HttpRequest(uri = Uri())
    }
  }

  "HttpMessage" should {
    "not throw a ClassCastException on header[`Content-Type`]" in {
      val entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString.fromString("hello akka"))
      HttpResponse(entity = entity).header[`Content-Type`] shouldBe Some(`Content-Type`(ContentTypes.`text/plain(UTF-8)`))
    }
    "retrieve all headers of a given class when calling headers[...]" in {
      val oneCookieHeader = `Set-Cookie`(HttpCookie("foo", "bar"))
      val anotherCookieHeader = `Set-Cookie`(HttpCookie("foz", "baz"))
      val hostHeader = Host("akka.io")
      val request = HttpRequest().withHeaders(oneCookieHeader, anotherCookieHeader, hostHeader)
      request.headers[`Set-Cookie`] should ===(Seq(oneCookieHeader, anotherCookieHeader))
    }
  }

}
