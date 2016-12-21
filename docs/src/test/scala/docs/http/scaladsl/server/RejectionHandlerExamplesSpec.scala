/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.{ Rejection, Route }
import akka.http.scaladsl.server.RouteResult.Complete

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
      .handle { case AuthorizationFailedRejection =>
        complete((Forbidden, "You're out of your depth!"))
      }
      .handle { case ValidationRejection(msg, _) =>
        complete((InternalServerError, "That wasn't valid! " + msg))
      }
      .handleAll[MethodRejection] { methodRejections =>
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
  //#custom-handler-example
}

class RejectionHandlerExamplesSpec extends RoutingSpec {
  import MyRejectionHandler._

  "example-1" in {
    //#example-1
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
    //#example-1
  }
  
  "example-2-all-exceptions-json" in {
    //#example-json
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.RejectionHandler

    implicit def myRejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
            // since all Akka default rejection responses are Strict this will handle all rejections
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            
            // we copy the response in order to keep all headers and status code, wrapping the message as hand rolled JSON
            // you could the entity using your favourite marshalling library (e.g. spray json or anything else) 
            res.copy(entity = HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))
            
          case x => x // pass through all other types of responses
        }
    
    val route =
      Route.seal(
        path("hello") {
          complete("Hello there")
        }
      )
      
    //#example-json
    Get("/nope") ~> route ~> check {
      status should === (StatusCodes.NotFound)
      contentType should === (ContentTypes.`application/json`)
      responseAs[String] should ===("""{"rejection": "The requested resource could not be found."}""")
    }
  }
  
  "example-3-custom-rejection-http-response" in {
    //#example-json
    import akka.http.scaladsl.model._
    import akka.http.scaladsl.server.RejectionHandler

    implicit def myRejectionHandler =
      RejectionHandler.default
        .mapRejectionResponse {
          case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
            // since all Akka default rejection responses are Strict this will handle all rejections
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            
            // we copy the response in order to keep all headers and status code, wrapping the message as hand rolled JSON
            // you could the entity using your favourite marshalling library (e.g. spray json or anything else) 
            res.copy(entity = HttpEntity(ContentTypes.`application/json`, s"""{"rejection": "$message"}"""))
            
          case x => x // pass through all other types of responses
        }
    
    val route =
      Route.seal(
        validate(check = false, "Whoops, bad request!") {
          complete("Hello there") 
        }
      )
      
    //#example-json
    Get("/hello") ~> route ~> check {
      status should === (StatusCodes.BadRequest)
      contentType should === (ContentTypes.`application/json`)
      responseAs[String] should ===("""{"rejection": "Whoops, bad request!"}""")
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
