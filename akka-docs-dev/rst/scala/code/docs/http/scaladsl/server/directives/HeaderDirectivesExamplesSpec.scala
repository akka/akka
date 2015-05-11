/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.MissingHeaderRejection
import akka.http.scaladsl.server.Route
import headers._
import StatusCodes._

class HeaderDirectivesExamplesSpec extends RoutingSpec {
  "headerValueByName-0" in {
    val route =
      headerValueByName("X-User-Id") { userId =>
        complete(s"The user is $userId")
      }

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

    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
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

    Get("/") ~> Host("example.com", 5043) ~> route ~> check {
      responseAs[String] shouldEqual "The port was 5043"
    }
    Get("/") ~> Route.seal(route) ~> check {
      status shouldEqual NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
    }
  }
  "headerValueByType-0" in {
    val route =
      headerValueByType[Origin]() { origin ⇒
        complete(s"The first origin was ${origin.originList.head}")
      }

    val originHeader = Origin(Seq(HttpOrigin("http://localhost:8080")))

    // extract a header if the type is matching
    Get("abc") ~> originHeader ~> route ~> check {
      responseAs[String] shouldEqual "The first origin was http://localhost:8080"
    }

    // reject a request if no header of the given type is present
    Get("abc") ~> route ~> check {
      rejection must beLike { case MissingHeaderRejection("Origin") ⇒ ok }
    }
  }
  "optionalHeaderValueByType-0" in {
    val route =
      optionalHeaderValueByType[Origin]() {
        case Some(origin) ⇒ complete(s"The first origin was ${origin.originList.head}")
        case None         ⇒ complete("No Origin header found.")
      }

    val originHeader = Origin(Seq(HttpOrigin("http://localhost:8080")))
    // extract Some(header) if the type is matching
    Get("abc") ~> originHeader ~> route ~> check {
      responseAs[String] shouldEqual "The first origin was http://localhost:8080"
    }

    // extract None if no header of the given type is present
    Get("abc") ~> route ~> check {
      responseAs[String] shouldEqual "No Origin header found."
    }
  }
}
