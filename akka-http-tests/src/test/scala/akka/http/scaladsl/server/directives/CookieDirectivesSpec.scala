/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import StatusCodes.OK
import headers._

class CookieDirectivesSpec extends RoutingSpec {

  val deletedTimeStamp = DateTime.fromIsoDateTimeString("1800-01-01T00:00:00")

  "The 'cookie' directive" should {
    "extract the respectively named cookie" in {
      Get() ~> addHeader(Cookie("fancy" -> "pants")) ~> {
        cookie("fancy") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "fancy=pants" }
    }
    "reject the request if the cookie is not present" in {
      Get() ~> {
        cookie("fancy") { echoComplete }
      } ~> check { rejection shouldEqual MissingCookieRejection("fancy") }
    }
    "properly pass through inner rejections" in {
      Get() ~> addHeader(Cookie("fancy" -> "pants")) ~> {
        cookie("fancy") { c ⇒ reject(ValidationRejection("Dont like " + c.value)) }
      } ~> check { rejection shouldEqual ValidationRejection("Dont like pants") }
    }
  }

  "The 'deleteCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in {
      Get() ~> {
        deleteCookie("myCookie", "test.com") { completeOk }
      } ~> check {
        status shouldEqual OK
        header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("myCookie", "deleted", expires = deletedTimeStamp,
          domain = Some("test.com"))))
      }
    }

    "support deleting multiple cookies at a time" in {
      Get() ~> {
        deleteCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        status shouldEqual OK
        headers.collect { case `Set-Cookie`(x) ⇒ x } shouldEqual List(
          HttpCookie("myCookie", "deleted", expires = deletedTimeStamp),
          HttpCookie("myCookie2", "deleted", expires = deletedTimeStamp))
      }
    }
  }

  "The 'optionalCookie' directive" should {
    "produce a `Some(cookie)` extraction if the cookie is present" in {
      Get() ~> Cookie("abc" -> "123") ~> {
        optionalCookie("abc") { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Some(abc=123)" }
    }
    "produce a `None` extraction if the cookie is not present" in {
      Get() ~> optionalCookie("abc") { echoComplete } ~> check { responseAs[String] shouldEqual "None" }
    }
    "let rejections from its inner route pass through" in {
      Get() ~> {
        optionalCookie("test-cookie") { _ ⇒
          validate(false, "ouch") { completeOk }
        }
      } ~> check { rejection shouldEqual ValidationRejection("ouch") }
    }
  }

  "The 'setCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com")) { completeOk }
      } ~> check {
        status shouldEqual OK
        header[`Set-Cookie`] shouldEqual Some(`Set-Cookie`(HttpCookie("myCookie", "test.com")))
      }
    }

    "support setting multiple cookies at a time" in {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        status shouldEqual OK
        headers.collect { case `Set-Cookie`(x) ⇒ x } shouldEqual List(
          HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com"))
      }
    }
  }
}
