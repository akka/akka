/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.model._
import headers._

class BasicDirectivesSpec extends RoutingSpec {

  "The `mapUnmatchedPath` directive" should {
    "map the unmatched path" in {
      Get("/abc") ~> {
        mapUnmatchedPath(_ / "def") {
          path("abc" / "def") { completeOk }
        }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The `extract` directive" should {
    "extract from the RequestContext" in {
      Get("/abc") ~> {
        extract(_.request.method.value) {
          echoComplete
        }
      } ~> check { responseAs[String] shouldEqual "GET" }
    }
  }

  "The `extractUri` directive" should {
    "extract from the request url" in {
      Get("https://example.com/foo") ~> {
        extractUri { echoComplete }
      } ~> check { responseAs[String] shouldEqual "https://example.com/foo" }
    }
    "extract from a X-Request-URI header" in {
      Get() ~> addHeader(RawHeader("x-request-uri", "http://example.com/foo")) ~> {
        extractUri { echoComplete }
      } ~> check { responseAs[String] shouldEqual "http://example.com/" }
    }
    "extract from request path and a Host header" in {
      Get("/foo/bar") ~> addHeader(`Host`("example.com")) ~> {
        extractUri { echoComplete }
      } ~> check { responseAs[String] shouldEqual "http://example.com/foo/bar" }
    }
  }
}
