/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.scaladsl.server

// format: OFF

object MyExplicitExceptionHandler {

  //#explicit-handler-example
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import StatusCodes._
  import akka.http.scaladsl.server._
  import Directives._
  import akka.stream.ActorMaterializer

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
        null // #hide
      }

    Http().bindAndHandle(route, "localhost", 8080)
  }

  //#explicit-handler-example
}

object MyImplicitExceptionHandler {

  //#implicit-handler-example
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import StatusCodes._
  import akka.http.scaladsl.server._
  import Directives._
  import akka.stream.ActorMaterializer

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
      null // #hide

    Http().bindAndHandle(route, "localhost", 8080)
  }

  //#implicit-handler-example
}

object RespondWithHeaderExceptionHandlerExample {
  //#respond-with-header-exceptionhandler-example
  import akka.actor.ActorSystem
  import akka.http.scaladsl.model.HttpResponse
  import akka.http.scaladsl.model.StatusCodes._
  import akka.http.scaladsl.model.headers.RawHeader
  import akka.http.scaladsl.server._
  import Directives._
  import akka.http.scaladsl.Http
  import akka.stream.ActorMaterializer
  import RespondWithHeaderExceptionHandler.route


  object RespondWithHeaderExceptionHandler {
    implicit def myExceptionHandler: ExceptionHandler =
      ExceptionHandler {
        case _: ArithmeticException =>
          extractUri { uri =>
            println(s"Request to $uri could not be handled normally")
            complete(HttpResponse(InternalServerError, entity = "Bad numbers, bad result!!!"))
          }
      }

    val greetingRoutes: Route = path("greetings") {
      complete("Hello!")
    }

    val divideRoutes: Route = path("divide") {
      complete((1 / 0).toString) //Will throw ArithmeticException
    }

    val route: Route =
      respondWithHeader(RawHeader("X-Outer-Header", "outer")) { // will apply, since it gets the response from the handler
        handleExceptions(myExceptionHandler) {
          greetingRoutes ~ divideRoutes ~ respondWithHeader(RawHeader("X-Inner-Header", "inner")) {
            throw new Exception("Boom!") //Will cause Internal server error,
            // only ArithmeticExceptions are handled by myExceptionHandler.
          }
        }
      }
  }

  object MyApp extends App {
    implicit val system = ActorSystem()
    implicit val materializer = ActorMaterializer()

    Http().bindAndHandle(route, "localhost", 8080)
  }
  //#respond-with-header-exceptionhandler-example
}


class ExceptionHandlerExamplesSpec extends RoutingSpec {

  "test explicit example" in {
    // tests:
    Get() ~> handleExceptions(MyExplicitExceptionHandler.myExceptionHandler) {
      _.complete((1 / 0).toString)
    } ~> check {
      responseAs[String] shouldEqual "Bad numbers, bad result!!!"
    }
  }

  "test implicit example" in {
    import MyImplicitExceptionHandler.myExceptionHandler
    import akka.http.scaladsl.server._
    // tests:
    Get() ~> Route.seal(ctx => ctx.complete((1 / 0).toString)) ~> check {
      responseAs[String] shouldEqual "Bad numbers, bad result!!!"
    }
  }

  "test respond with outer header only example" in {
    import akka.http.scaladsl.model.headers.RawHeader
    import RespondWithHeaderExceptionHandlerExample.RespondWithHeaderExceptionHandler.route

    Get("/divide") ~> route ~> check {
      header("X-Outer-Header") shouldEqual Some(RawHeader("X-Outer-Header", "outer"))
      responseAs[String] shouldEqual "Bad numbers, bad result!!!"
    }
  }
}
