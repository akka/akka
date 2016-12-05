/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.server._

class DirectiveExamplesSpec extends RoutingSpec {

  // format: OFF

  "example-1" in {
    //#example-1
    val route: Route =
      path("order" / IntNumber) { id =>
        get {
          complete {
            "Received GET request for order " + id
          }
        } ~
        put {
          complete {
            "Received PUT request for order " + id
          }
        }
      }
    verify(route) // hide
    //#example-1
  }

  "example-2" in {
    //#example-2
    def innerRoute(id: Int): Route =
      get {
        complete {
          "Received GET request for order " + id
        }
      } ~
      put {
        complete {
          "Received PUT request for order " + id
        }
      }

    val route: Route = path("order" / IntNumber) { id => innerRoute(id) }
    verify(route) // hide
    //#example-2
  }

  "example-3" in {
    //#example-3
    val route =
      path("order" / IntNumber) { id =>
        (get | put) { ctx =>
          ctx.complete(s"Received ${ctx.request.method.name} request for order $id")
        }
      }
    verify(route) // hide
    //#example-3
  }

  "example-4" in {
    //#example-4
    val route =
      path("order" / IntNumber) { id =>
        (get | put) {
          extractMethod { m =>
            complete(s"Received ${m.name} request for order $id")
          }
        }
      }
    verify(route) // hide
    //#example-4
  }

  "example-5" in {
    //#example-5
    val getOrPut = get | put
    val route =
      path("order" / IntNumber) { id =>
        getOrPut {
          extractMethod { m =>
            complete(s"Received ${m.name} request for order $id")
          }
        }
      }
    verify(route) // hide
    //#example-5
  }

  "example-6" in {
    //#example-6
    val getOrPut = get | put
    val route =
      (path("order" / IntNumber) & getOrPut & extractMethod) { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // hide
    //#example-6
  }

  "example-7" in {
    //#example-7
    val orderGetOrPutWithMethod =
      path("order" / IntNumber) & (get | put) & extractMethod
    val route =
      orderGetOrPutWithMethod { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // hide
    //#example-7
  }

  def verify(route: Route) = {
    Get("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received GET request for order 42" }
    Put("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received PUT request for order 42" }
    Get("/") ~> route ~> check { handled shouldEqual false }
  }
}
