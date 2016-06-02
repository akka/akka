/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import headers._
import docs.http.scaladsl.server.RoutingSpec
import java.net.InetAddress

class MiscDirectivesExamplesSpec extends RoutingSpec {

  "extractClientIP-example" in {
    val route = extractClientIP { ip =>
      complete("Client's ip is " + ip.toOption.map(_.getHostAddress).getOrElse("unknown"))
    }

    // tests:
    Get("/").withHeaders(`Remote-Address`(RemoteAddress(InetAddress.getByName("192.168.3.12")))) ~> route ~> check {
      responseAs[String] shouldEqual "Client's ip is 192.168.3.12"
    }
  }
  "rejectEmptyResponse-example" in {
    val route = rejectEmptyResponse {
      path("even" / IntNumber) { i =>
        complete {
          // returns Some(evenNumberDescription) or None
          Option(i).filter(_ % 2 == 0).map { num =>
            s"Number $num is even."
          }
        }
      }
    }

    // tests:
    Get("/even/23") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    Get("/even/28") ~> route ~> check {
      responseAs[String] shouldEqual "Number 28 is even."
    }
  }
  "requestEntityEmptyPresent-example" in {
    val route =
      requestEntityEmpty {
        complete("request entity empty")
      } ~
        requestEntityPresent {
          complete("request entity present")
        }

    // tests:
    Post("/", "text") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "request entity present"
    }
    Post("/") ~> route ~> check {
      responseAs[String] shouldEqual "request entity empty"
    }
  }
  "selectPreferredLanguage-example" in {
    val request = Get() ~> `Accept-Language`(
      Language("en-US"),
      Language("en") withQValue 0.7f,
      LanguageRange.`*` withQValue 0.1f,
      Language("de") withQValue 0.5f)

    request ~> {
      selectPreferredLanguage("en", "en-US") { lang =>
        complete(lang.toString)
      }
    } ~> check { responseAs[String] shouldEqual "en-US" }

    request ~> {
      selectPreferredLanguage("de-DE", "hu") { lang =>
        complete(lang.toString)
      }
    } ~> check { responseAs[String] shouldEqual "de-DE" }
  }
  "validate-example" in {
    val route =
      extractUri { uri =>
        validate(uri.path.toString.size < 5, s"Path too long: '${uri.path.toString}'") {
          complete(s"Full URI: $uri")
        }
      }

    // tests:
    Get("/234") ~> route ~> check {
      responseAs[String] shouldEqual "Full URI: http://example.com/234"
    }
    Get("/abcdefghijkl") ~> route ~> check {
      rejection shouldEqual ValidationRejection("Path too long: '/abcdefghijkl'", None)
    }
  }
}
