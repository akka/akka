/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.stream.ActorFlowMaterializer

object MyHandler {
  //# example-1
  import akka.http.scaladsl.model.HttpResponse
  import akka.http.scaladsl.model.StatusCodes._
  import akka.http.scaladsl.server._
  import Directives._

  implicit def myExceptionHandler =
    ExceptionHandler {
      case e: ArithmeticException =>
        extractUri { uri =>
          logWarning(s"Request to $uri could not be handled normally")
          complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
        }
    }

  object MyApp {
    implicit val system = ActorSystem()
    import system.dispatcher
    implicit val materializer = ActorFlowMaterializer()

    def handler = Route.handlerFlow(`<my-route-definition>`)
  }
  //#

  def `<my-route-definition>`: Route = null
  def logWarning(str: String): Unit = {}
}

class ExceptionHandlerExamplesSpec extends RoutingSpec {
  import MyHandler._

  "example" in {
    Get() ~> Route.seal(ctx => ctx.complete((1 / 0).toString)) ~> check {
      responseAs[String] === "Bad numbers, bad result!!!"
    }
  }
}
