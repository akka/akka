/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route

class MethodDirectivesExamplesSpec extends RoutingSpec {

  "delete-method" in {
    val route = delete { complete("This is a DELETE request.") }

    Delete("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a DELETE request."
    }
  }

  "get-method" in {
    val route = get { complete("This is a GET request.") }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a GET request."
    }
  }

  "head-method" in {
    val route = head { complete("This is a HEAD request.") }

    Head("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a HEAD request."
    }
  }

  "options-method" in {
    val route = options { complete("This is an OPTIONS request.") }

    Options("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is an OPTIONS request."
    }
  }

  "patch-method" in {
    val route = patch { complete("This is a PATCH request.") }

    Patch("/", "patch content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PATCH request."
    }
  }

  "post-method" in {
    val route = post { complete("This is a POST request.") }

    Post("/", "post content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a POST request."
    }
  }

  "put-method" in {
    val route = put { complete("This is a PUT request.") }

    Put("/", "put content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PUT request."
    }
  }

  "method-example" in {
    val route = method(HttpMethods.PUT) { complete("This is a PUT request.") }

    Put("/", "put content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PUT request."
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.MethodNotAllowed
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: PUT"
    }
  }

  "extractMethod-example" in {
    val route =
      get {
        complete("This is a GET request.")
      } ~
        extractMethod { method =>
          complete(s"This ${method.name} request, clearly is not a GET!")
        }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a GET request."
    }

    Put("/") ~> route ~> check {
      responseAs[String] shouldEqual "This PUT request, clearly is not a GET!"
    }
    Head("/") ~> route ~> check {
      responseAs[String] shouldEqual "This HEAD request, clearly is not a GET!"
    }
  }

  "overrideMethodWithParameter-0" in {
    val route =
      overrideMethodWithParameter("method") {
        get {
          complete("This looks like a GET request.")
        } ~
          post {
            complete("This looks like a POST request.")
          }
      }

    Get("/?method=POST") ~> route ~> check {
      responseAs[String] shouldEqual "This looks like a POST request."
    }
    Post("/?method=get") ~> route ~> check {
      responseAs[String] shouldEqual "This looks like a GET request."
    }

    Get("/?method=hallo") ~> route ~> check {
      status shouldEqual StatusCodes.NotImplemented
    }
  }

}