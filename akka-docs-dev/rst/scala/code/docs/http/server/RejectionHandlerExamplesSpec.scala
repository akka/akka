/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.server

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer

import akka.http.scaladsl.server.{ Route, MissingCookieRejection }

import scala.concurrent.ExecutionContext

object MyRejectionHandler {
  //# example-1
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server._
  import StatusCodes._
  import Directives._

  implicit val myRejectionHandler = RejectionHandler.newBuilder()
    .handle {
      case MissingCookieRejection(cookieName) =>
        complete(HttpResponse(BadRequest, entity = "No cookies, no service!!!"))
    }
    .result()

  object MyApp {
    implicit val system = ActorSystem()
    import system.dispatcher
    implicit val materializer = ActorFlowMaterializer()

    def handler = Route.handlerFlow(`<my-route-definition>`)
  }
  //#

  def `<my-route-definition>`: Route = null
}

class RejectionHandlerExamplesSpec extends RoutingSpec {
  import MyRejectionHandler._

  "example" in {
    Get() ~> Route.seal(reject(MissingCookieRejection("abc"))) ~> check {
      responseAs[String] === "No cookies, no service!!!"
    }
  }

  "example-2" in {
    import akka.http.scaladsl.coding.Gzip

    val route =
      path("order") {
        get {
          complete("Received GET")
        } ~
          post {
            decodeRequestWith(Gzip) {
              complete("Received POST")
            }
          }
      }
  }
}
