/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.server
package directives

import akka.http.scaladsl.server._
import akka.http.scaladsl.model.headers.{ HttpCookie, Cookie, `Set-Cookie` }
import akka.http.scaladsl.model.DateTime

class CookieDirectivesExamplesSpec extends RoutingSpec {
  "cookie" in {
    val route =
      cookie("userName") { nameCookie =>
        complete(s"The logged in user is '${nameCookie.content}'")
      }

    Get("/") ~> Cookie(HttpCookie("userName", "paul")) ~> route ~> check {
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
        case Some(nameCookie) => complete(s"The logged in user is '${nameCookie.content}'")
        case None             => complete("No user logged in")
      }

    Get("/") ~> Cookie(HttpCookie("userName", "paul")) ~> route ~> check {
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

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "The user was logged out"
      header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("userName", content = "deleted", expires = Some(DateTime.MinValue))))
    }
  }
  "setCookie" in {
    val route =
      setCookie(HttpCookie("userName", content = "paul")) {
        complete("The user was logged in")
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "The user was logged in"
      header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("userName", content = "paul")))
    }
  }
}
