/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

// format: OFF

object MyRejectionHandler {

  //#custom-handler-example
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server._
  import StatusCodes._
  import Directives._

  implicit def myRejectionHandler =
    RejectionHandler.newBuilder()
      .handle { case MissingCookieRejection(cookieName) =>
        complete(HttpResponse(BadRequest, entity = "No cookies, no service!!!"))
      }
      .handle { case AuthorizationFailedRejection ⇒
        complete((Forbidden, "You're out of your depth!"))
      }
      .handle { case ValidationRejection(msg, _) ⇒
        complete((InternalServerError, "That wasn't valid! " + msg))
      }
      .handleAll[MethodRejection] { methodRejections ⇒
        val names = methodRejections.map(_.supported.name)
        complete((MethodNotAllowed, s"Can't do that! Supported: ${names mkString " or "}!"))
      }
      .handleNotFound { complete((NotFound, "Not here!")) }
      .result()

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

class RejectionHandlerExamplesSpec extends RoutingSpec {
  import MyRejectionHandler._

  "example-1" in {
    import akka.http.scaladsl.coding.Gzip

    val route =
      path("order") {
        get {
          complete("Received GET")
        } ~
        post {
          decodeRequestWith(Gzip) {
            complete("Received compressed POST")
          }
        }
      }
  }

  "test custom handler example" in {
    import akka.http.scaladsl.server._
    val route = Route.seal(reject(MissingCookieRejection("abc")))

    // tests:
    Get() ~> route ~> check {
      responseAs[String] === "No cookies, no service!!!"
    }
  }
}
