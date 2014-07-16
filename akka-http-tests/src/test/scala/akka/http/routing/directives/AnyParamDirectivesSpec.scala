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

import akka.http.model.FormData

class AnyParamDirectivesSpec extends RoutingSpec {
  "tests" should {
    "be resurrected" in pending
  }
  /*"when used with a single required parameter" should {
    val route = (path("test") & anyParam("x")) { echoComplete }

    "extract the parameter from query parameters" in {
      Get("/test?x=1") ~> route ~> check {
        responseAs[String] mustEqual "1"
      }
    }

    "extract the parameter from form" in {
      Post("/test", FormData(Map("x" -> "1"))) ~> route ~> check {
        responseAs[String] mustEqual "1"
      }
    }

    "prefer form over query pamaeters" in {
      Post("/test?x=2", FormData(Map("x" -> "1"))) ~> route ~> check {
        responseAs[String] mustEqual "1"
      }
    }
  }

  "when used with a single optional parameter" should {
    val route = (path("test") & anyParam("x"?)) { echoComplete }

    "extract the parameter from query parameters" in {
      Get("/test?x=1") ~> route ~> check {
        responseAs[String] mustEqual "Some(1)"
      }
    }

    "extract the parameter from form" in {
      Post("/test", FormData(Map("x" -> "1"))) ~> route ~> check {
        responseAs[String] mustEqual "Some(1)"
      }
    }

    "extract None if no query parameters" in {
      Get("/test") ~> route ~> check {
        responseAs[String] mustEqual "None"
      }
    }

    "extract None if no form" in {
      Post("/test", FormData(Seq())) ~> route ~> check {
        responseAs[String] mustEqual "None"
      }
    }
  }

  "when used with two required parameters" should {
    val route = (path("test") & anyParam("x", "y")) { echoComplete2 }

    "extract the parameters from query parameters" in {
      Get("/test?x=1&y=2") ~> route ~> check {
        responseAs[String] mustEqual "1 2"
      }
    }

    "extract the parameters from form" in {
      Post("/test", FormData(Map("x" -> "1", "y" -> "2"))) ~> route ~> check {
        responseAs[String] mustEqual "1 2"
      }
    }

    "extract the parameters both from form and query parameters" in {
      Post("/test?x=1", FormData(Map("y" -> "2"))) ~> route ~> check {
        responseAs[String] mustEqual "1 2"
      }
    }
  }

  "when used with two optional parameters" should {
    val route = (path("test") & anyParam("x"?, "y"?)) { echoComplete2 }

    "extract the parameters from query parameters" in {
      Get("/test?x=1&y=2") ~> route ~> check {
        responseAs[String] mustEqual "Some(1) Some(2)"
      }
    }

    "extract the parameters from form" in {
      Post("/test", FormData(Map("x" -> "1", "y" -> "2"))) ~> route ~> check {
        responseAs[String] mustEqual "Some(1) Some(2)"
      }
    }

    "extract only the parameters that are present, from query parameters" in {
      Get("/test?x=1") ~> route ~> check {
        responseAs[String] mustEqual "Some(1) None"
      }
    }

    "extract only the parameters that are present, from form" in {
      Post("/test", FormData(Map("y" -> "2"))) ~> route ~> check {
        responseAs[String] mustEqual "None Some(2)"
      }
    }
  }

  "when used with type conversions" should {
    val route = (path("test") & anyParam("x".as[Int], "y".as[Boolean])) { echoComplete2 }

    "extract the parameters with correct types, from query parameters" in {
      Get("/test?x=1&y=false") ~> route ~> check {
        responseAs[String] mustEqual "1 false"
      }
    }

    "extract the parameters with correct types, from the form" in {
      Post("/test", FormData(Map("x" -> "10", "y" -> "true"))) ~> route ~> check {
        responseAs[String] mustEqual "10 true"
      }
    }
  }

  "when used with a symbol" should {
    val route = (path("test") & anyParam('x)) { echoComplete }

    "extract the parameter from query parameters" in {
      Get("/test?x=1") ~> route ~> check {
        responseAs[String] mustEqual "1"
      }
    }
  }*/
}