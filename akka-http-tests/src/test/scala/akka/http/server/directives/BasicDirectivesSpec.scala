/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

class BasicDirectivesSpec extends RoutingSpec {

  "The `rewriteUnmatchedPath` directive" should {
    "rewrite the unmatched path" in {
      Get("/abc") ~> {
        rewriteUnmatchedPath(_ / "def") {
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
}