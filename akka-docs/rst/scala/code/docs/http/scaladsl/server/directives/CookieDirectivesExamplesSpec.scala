/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers.{ HttpCookie, Cookie, `Set-Cookie` }
import docs.http.scaladsl.server.RoutingSpec
import akka.http.scaladsl.model.DateTime

class CookieDirectivesExamplesSpec extends RoutingSpec {
  "cookie" in {
    val route =
      cookie("userName") { nameCookie =>
        complete(s"The logged in user is '${nameCookie.value}'")
      }

    // tests:
    Get("/") ~> Cookie("userName" -> "paul") ~> route ~> check {
      responseAs[String] shouldEqual "The logged in user is 'paul'"
    }
    // missing cookie
    Get("/") ~> route ~> check {
      rejection shouldEqual MissingCookieRejection("userName")
    }
    Get("/") ~> Route.seal(route) ~> check {
      responseAs[String] shouldEqual "Request is missing required cookie 'userName'"
    }
  }
  "optionalCookie" in {
    val route =
      optionalCookie("userName") {
        case Some(nameCookie) => complete(s"The logged in user is '${nameCookie.value}'")
        case None             => complete("No user logged in")
      }

    // tests:
    Get("/") ~> Cookie("userName" -> "paul") ~> route ~> check {
      responseAs[String] shouldEqual "The logged in user is 'paul'"
    }
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "No user logged in"
    }
  }
  "deleteCookie" in {
    val route =
      deleteCookie("userName") {
        complete("The user was logged out")
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "The user was logged out"
      header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("userName", value = "deleted", expires = Some(DateTime.MinValue))))
    }
  }
  "setCookie" in {
    val route =
      setCookie(HttpCookie("userName", value = "paul")) {
        complete("The user was logged in")
      }

    // tests:
    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "The user was logged in"
      header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("userName", value = "paul")))
    }
  }
}
