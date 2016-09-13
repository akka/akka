/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

// format: OFF

object MyExplicitExceptionHandler {

  //#explicit-handler-example
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server._
  import StatusCodes._
  import Directives._

  val myExceptionHandler = ExceptionHandler {
    case _: ArithmeticException =>
      extractUri { uri =>
        println(s"Request to $uri could not be handled normally")
        complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
      }
  }

  object MyApp extends App {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val route: Route =
      handleExceptions(myExceptionHandler) {
        // ... some route structure
        null // hide
      }

    Http().bindAndHandle(route, "localhost", 8080)
  }
  //#
}

object MyImplicitExceptionHandler {

  //#implicit-handler-example
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server._
  import StatusCodes._
  import Directives._

  implicit def myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case _: ArithmeticException =>
        extractUri { uri =>
          println(s"Request to $uri could not be handled normally")
          complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
        }
    }

  object MyApp extends App {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    val route: Route =
    // ... some route structure
      null // hide

    Http().bindAndHandle(route, "localhost", 8080)
  }
  //#
}

class ExceptionHandlerExamplesSpec extends RoutingSpec {

  "test explicit example" in {
    // tests:
    Get() ~> handleExceptions(MyExplicitExceptionHandler.myExceptionHandler) {
      _.complete((1 / 0).toString)
    } ~> check {
      responseAs[String] === "Bad numbers, bad result!!!"
    }
  }

  "test implicit example" in {
    import akka.http.scaladsl.server._
    import MyImplicitExceptionHandler.myExceptionHandler
    // tests:
    Get() ~> Route.seal(ctx => ctx.complete((1 / 0).toString)) ~> check {
      responseAs[String] === "Bad numbers, bad result!!!"
    }
  }
}
