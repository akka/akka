/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest

class DirectiveExamplesSpec extends RoutingSpec {

  // format: OFF

  "example-1" in {
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
  }

  "example-2" in {
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
  }

  "example-3" in {
    val route =
      path("order" / IntNumber) { id =>
        (get | put) { ctx =>
          ctx.complete(s"Received ${ctx.request.method.name} request for order $id")
        }
      }
    verify(route) // hide
  }

  "example-4" in {
    val route =
      path("order" / IntNumber) { id =>
        (get | put) {
          extractMethod { m =>
            complete(s"Received ${m.name} request for order $id")
          }
        }
      }
    verify(route) // hide
  }

  "example-5" in {
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
  }

  "example-6" in {
    val getOrPut = get | put
    val route =
      (path("order" / IntNumber) & getOrPut & extractMethod) { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // hide
  }

  "example-7" in {
    val orderGetOrPutWithMethod =
      path("order" / IntNumber) & (get | put) & extractMethod
    val route =
      orderGetOrPutWithMethod { (id, m) =>
        complete(s"Received ${m.name} request for order $id")
      }
    verify(route) // hide
  }

  def verify(route: Route) = {
    Get("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received GET request for order 42" }
    Put("/order/42") ~> route ~> check { responseAs[String] shouldEqual "Received PUT request for order 42" }
    Get("/") ~> route ~> check { handled shouldEqual false }
  }
}
