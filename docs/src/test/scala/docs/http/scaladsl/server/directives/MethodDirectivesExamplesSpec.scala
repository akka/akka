/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import docs.http.scaladsl.server.RoutingSpec

class MethodDirectivesExamplesSpec extends RoutingSpec {

  "delete-method" in {
    //#delete-method
    val route = delete { complete("This is a DELETE request.") }

    // tests:
    Delete("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a DELETE request."
    }
    //#delete-method
  }

  "get-method" in {
    //#get-method
    val route = get { complete("This is a GET request.") }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a GET request."
    }
    //#get-method
  }

  "head-method" in {
    //#head-method
    val route = head { complete("This is a HEAD request.") }

    // tests:
    Head("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a HEAD request."
    }
    //#head-method
  }

  "options-method" in {
    //#options-method
    val route = options { complete("This is an OPTIONS request.") }

    // tests:
    Options("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is an OPTIONS request."
    }
    //#options-method
  }

  "patch-method" in {
    //#patch-method
    val route = patch { complete("This is a PATCH request.") }

    // tests:
    Patch("/", "patch content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PATCH request."
    }
    //#patch-method
  }

  "post-method" in {
    //#post-method
    val route = post { complete("This is a POST request.") }

    // tests:
    Post("/", "post content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a POST request."
    }
    //#post-method
  }

  "put-method" in {
    //#put-method
    val route = put { complete("This is a PUT request.") }

    // tests:
    Put("/", "put content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PUT request."
    }
    //#put-method
  }

  "method-example" in {
    //#method-example
    val route = method(HttpMethods.PUT) { complete("This is a PUT request.") }

    // tests:
    Put("/", "put content") ~> route ~> check {
      responseAs[String] shouldEqual "This is a PUT request."
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.MethodNotAllowed
      responseAs[String] shouldEqual "HTTP method not allowed, supported methods: PUT"
    }
    //#method-example
  }

  "extractMethod-example" in {
    //#extractMethod-example
    val route =
      get {
        complete("This is a GET request.")
      } ~
        extractMethod { method =>
          complete(s"This ${method.name} request, clearly is not a GET!")
        }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "This is a GET request."
    }

    Put("/") ~> route ~> check {
      responseAs[String] shouldEqual "This PUT request, clearly is not a GET!"
    }
    Head("/") ~> route ~> check {
      responseAs[String] shouldEqual "This HEAD request, clearly is not a GET!"
    }
    //#extractMethod-example
  }

  "overrideMethodWithParameter-0" in {
    //#overrideMethodWithParameter-0
    val route =
      overrideMethodWithParameter("method") {
        get {
          complete("This looks like a GET request.")
        } ~
          post {
            complete("This looks like a POST request.")
          }
      }

    // tests:
    Get("/?method=POST") ~> route ~> check {
      responseAs[String] shouldEqual "This looks like a POST request."
    }
    Post("/?method=get") ~> route ~> check {
      responseAs[String] shouldEqual "This looks like a GET request."
    }

    Get("/?method=hallo") ~> route ~> check {
      status shouldEqual StatusCodes.NotImplemented
    }
    //#overrideMethodWithParameter-0
  }

}
