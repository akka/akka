/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import headers.Host
import org.scalatest.{ Matchers, WordSpec }

class HttpMessageSpec extends WordSpec with Matchers {

  def test(uri: String, hostHeader: Host, effectiveUri: String) =
    HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null) shouldEqual Uri(effectiveUri)

  def fail(uri: String, hostHeader: Host) =
    an[IllegalUriException] should be thrownBy
      HttpRequest.effectiveUri(Uri(uri), List(hostHeader), securedConnection = false, null)

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
  }
}
