/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import akka.util.ByteString
import headers.Host
import headers.`Content-Type`
import org.scalatest.{ Matchers, WordSpec }

class HttpMessageSpec extends WordSpec with Matchers {

  def test(uri: String, hostHeader: Host, effectiveUri: String) =
    HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null) shouldEqual Uri(effectiveUri)

  def fail(uri: String, hostHeader: Host) =
    an[IllegalUriException] should be thrownBy
      HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null)

  def failWithNoHostHeader(hostHeader: Option[Host], details: String) = {
    val thrown = the[IllegalUriException] thrownBy
      HttpRequest.effectiveUri(Uri("/relative"), hostHeader.toList, securedConnection = false, Host(""))

    thrown should have message
      s"Cannot establish effective URI of request to `/relative`, request has a relative URI and $details; " +
      "consider setting `akka.http.server.default-host-header`"
  }

  "HttpRequest" should {
    "provide an effective URI for relative URIs or matching Host-headers" in {
      test("/segment", Host("example.com"), "http://example.com/segment")
      test("http://example.com/", Host("example.com"), "http://example.com/")
      test("http://example.com:8080/", Host("example.com", 8080), "http://example.com:8080/")
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
  }

  "HttpMessage" should {
    "not throw a ClassCastException on header[`Content-Type`]" in {
      val entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString.fromString("hello akka"))
      HttpResponse(entity = entity).header[`Content-Type`] shouldBe Some(`Content-Type`(ContentTypes.`text/plain(UTF-8)`))
    }
  }

}
