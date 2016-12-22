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
    //#headerValueByName-0
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
    //#headerValueByName-0
  }
  "headerValue-0" in {
    //#headerValue-0
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
    //#headerValue-0
  }
  "headerValue-or-default-0" in {
    //#headerValue-or-default-0
    val exampleHeaderValue = "exampleHeaderValue".toLowerCase
    def extractExampleHeader: HttpHeader => Option[String] = {
      case HttpHeader(`exampleHeaderValue`, value) => Some(value)
      case _                                       => None
    }

    val route =
      (headerValue(extractExampleHeader) | provide("newValue")) { value =>
        complete(s"headerValue $value")
      }

    // tests:
    Get("/") ~> RawHeader("exampleHeaderValue", "theHeaderValue") ~> route ~> check {
      responseAs[String] shouldEqual "headerValue theHeaderValue"
    }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "headerValue newValue"
    }
    //#headerValue-or-default-0
  }
  "optionalHeaderValue-0" in {
    //#optionalHeaderValue-0
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
    //#optionalHeaderValue-0
  }
  "optionalHeaderValueByName-0" in {
    //#optionalHeaderValueByName-0
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
    //#optionalHeaderValueByName-0
  }
  "headerValuePF-0" in {
    //#headerValuePF-0
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
    //#headerValuePF-0
  }
  "optionalHeaderValuePF-0" in {
    //#optionalHeaderValuePF-0
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
    //#optionalHeaderValuePF-0
  }
  "headerValueByType-0" in {
    //#headerValueByType-0
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
    //#headerValueByType-0
  }
  "optionalHeaderValueByType-0" in {
    //#optionalHeaderValueByType-0
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
    //#optionalHeaderValueByType-0
  }
  "checkSameOrigin-0" in {
    //#checkSameOrigin-0
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
    //#checkSameOrigin-0
  }
}
