/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{ InvalidOriginRejection, MissingHeaderRejection, Route }
import docs.http.scaladsl.server.RoutingSpec
import org.scalatest.Inside

class HeaderDirectivesExamplesSpec extends RoutingSpec with Inside {
  "headerValueByName-0" in {
    val route =
      headerValueByName("X-User-Id") { userId =>
        complete(s"The user is $userId")
      }

    // tests:
    Get("/") ~> RawHeader("X-User-Id", "Joe42") ~> route ~> check {
      responseAs[String] shouldEqual "The user is Joe42"
    }

    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual BadRequest
      responseAs[String] shouldEqual "Request is missing required HTTP header 'X-User-Id'"
    }
  }
  "headerValue-0" in {
    def extractHostPort: HttpHeader => Option[Int] = {
      case h: `Host` => Some(h.port)
      case x         => None
    }

    val route =
      headerValue(extractHostPort) { port =>
        complete(s"The port was $port")
      }

    // tests:
    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
    }
  }
  "optionalHeaderValue-0" in {
    def extractHostPort: HttpHeader => Option[Int] = {
      case h: `Host` => Some(h.port)
      case x         => None
    }

    val route =
      optionalHeaderValue(extractHostPort) {
        case Some(port) => complete(s"The port was $port")
        case None       => complete(s"The port was not provided explicitly")
      } ~ // can also be written as:
        optionalHeaderValue(extractHostPort) { port =>
          complete {
            port match {
              case Some(p) => s"The port was $p"
              case _       => "The port was not provided explicitly"
            }
          }
        }

    // tests:
    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "The port was not provided explicitly"
    }
  }
  "optionalHeaderValueByName-0" in {
    val route =
      optionalHeaderValueByName("X-User-Id") {
        case Some(userId) => complete(s"The user is $userId")
        case None         => complete(s"No user was provided")
      } ~ // can also be written as:
        optionalHeaderValueByName("port") { port =>
          complete {
            port match {
              case Some(p) => s"The user is $p"
              case _       => "No user was provided"
            }
          }
        }

    // tests:
    Get("/") ~> RawHeader("X-User-Id", "Joe42") ~> route ~> check {
      responseAs[String] shouldEqual "The user is Joe42"
    }
    Get("/") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "No user was provided"
    }
  }
  "headerValuePF-0" in {
    def extractHostPort: PartialFunction[HttpHeader, Int] = {
      case h: `Host` => h.port
    }

    val route =
      headerValuePF(extractHostPort) { port =>
        complete(s"The port was $port")
      }

    // tests:
    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
    }
  }
  "optionalHeaderValuePF-0" in {
    def extractHostPort: PartialFunction[HttpHeader, Int] = {
      case h: `Host` => h.port
    }

    val route =
      optionalHeaderValuePF(extractHostPort) {
        case Some(port) => complete(s"The port was $port")
        case None       => complete(s"The port was not provided explicitly")
      } ~ // can also be written as:
        optionalHeaderValuePF(extractHostPort) { port =>
          complete {
            port match {
              case Some(p) => s"The port was $p"
              case _       => "The port was not provided explicitly"
            }
          }
        }

    // tests:
    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "The port was not provided explicitly"
    }
  }
  "headerValueByType-0" in {
    val route =
      headerValueByType[Origin]() { origin =>
        complete(s"The first origin was ${origin.origins.head}")
      }

    val originHeader = Origin(HttpOrigin("http://localhost:8080"))

    // tests:
    // extract a header if the type is matching
    Get("abc") ~> originHeader ~> route ~> check {
      responseAs[String] shouldEqual "The first origin was http://localhost:8080"
    }

    // reject a request if no header of the given type is present
    Get("abc") ~> route ~> check {
      inside(rejection) { case MissingHeaderRejection("Origin") => }
    }
  }
  "optionalHeaderValueByType-0" in {
    val route =
      optionalHeaderValueByType[Origin]() {
        case Some(origin) => complete(s"The first origin was ${origin.origins.head}")
        case None         => complete("No Origin header found.")
      }

    val originHeader = Origin(HttpOrigin("http://localhost:8080"))

    // tests:
    // extract Some(header) if the type is matching
    Get("abc") ~> originHeader ~> route ~> check {
      responseAs[String] shouldEqual "The first origin was http://localhost:8080"
    }

    // extract None if no header of the given type is present
    Get("abc") ~> route ~> check {
      responseAs[String] shouldEqual "No Origin header found."
    }
  }
  "checkSameOrigin-0" in {
    val correctOrigin = HttpOrigin("http://localhost:8080")
    val route = checkSameOrigin(HttpOriginRange(correctOrigin)) {
      complete("Result")
    }

    // tests:
    // handle request with correct origin headers
    Get("abc") ~> Origin(correctOrigin) ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "Result"
    }

    // reject request with missed origin header
    Get("abc") ~> route ~> check {
      inside(rejection) {
        case MissingHeaderRejection(headerName) ⇒ headerName shouldEqual Origin.name
      }
    }

    // rejects request with invalid origin headers
    val invalidHttpOrigin = HttpOrigin("http://invalid.com")
    val invalidOriginHeader = Origin(invalidHttpOrigin)
    Get("abc") ~> invalidOriginHeader ~> route ~> check {
      inside(rejection) {
        case InvalidOriginRejection(allowedOrigins) ⇒
          allowedOrigins shouldEqual Seq(correctOrigin)
      }
    }
    Get("abc") ~> invalidOriginHeader ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[String] should include(s"${correctOrigin.value}")
    }
  }
}
