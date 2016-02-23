/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

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
}