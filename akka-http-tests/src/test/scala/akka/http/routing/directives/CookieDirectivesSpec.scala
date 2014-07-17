/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.model._
import akka.http.routing._
import akka.http.util.DateTime
import headers._
import StatusCodes.OK

class CookieDirectivesSpec extends RoutingSpec {

  val deletedTimeStamp = DateTime.fromIsoDateTimeString("1800-01-01T00:00:00")

  "The 'cookie' directive" should {
    "extract the respectively named cookie" in pendingUntilFixed {
      Get() ~> addHeader(Cookie(HttpCookie("fancy", "pants"))) ~> {
        cookie("fancy") { echoComplete }
      } ~> check { responseAs[String] mustEqual "fancy=pants" }
    }
    "reject the request if the cookie is not present" in pendingUntilFixed {
      Get() ~> {
        cookie("fancy") { echoComplete }
      } ~> check { rejection mustEqual MissingCookieRejection("fancy") }
    }
    "properly pass through inner rejections" in pendingUntilFixed {
      Get() ~> addHeader(Cookie(HttpCookie("fancy", "pants"))) ~> {
        cookie("fancy") { c ⇒ reject(ValidationRejection("Dont like " + c.content)) }
      } ~> check { rejection mustEqual ValidationRejection("Dont like pants") }
    }
  }

  "The 'deleteCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in pendingUntilFixed {
      Get() ~> {
        deleteCookie("myCookie", "test.com") { completeOk }
      } ~> check {
        status mustEqual OK
        header[`Set-Cookie`] mustEqual Some(`Set-Cookie`(HttpCookie("myCookie", "deleted", expires = deletedTimeStamp,
          domain = Some("test.com"))))
      }
    }

    "support deleting multiple cookies at a time" in pendingUntilFixed {
      Get() ~> {
        deleteCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        status mustEqual OK
        headers.collect { case `Set-Cookie`(x) ⇒ x } mustEqual List(
          HttpCookie("myCookie", "deleted", expires = deletedTimeStamp),
          HttpCookie("myCookie2", "deleted", expires = deletedTimeStamp))
      }
    }
  }

  "The 'optionalCookie' directive" should {
    "produce a `Some(cookie)` extraction if the cookie is present" in pendingUntilFixed {
      Get() ~> Cookie(HttpCookie("abc", "123")) ~> {
        optionalCookie("abc") { echoComplete }
      } ~> check { responseAs[String] mustEqual "Some(abc=123)" }
    }
    "produce a `None` extraction if the cookie is not present" in pendingUntilFixed {
      Get() ~> optionalCookie("abc") { echoComplete } ~> check { responseAs[String] mustEqual "None" }
    }
    "let rejections from its inner route pass through" in pendingUntilFixed {
      Get() ~> {
        optionalCookie("test-cookie") { _ ⇒
          validate(false, "ouch") { completeOk }
        }
      } ~> check { rejection mustEqual ValidationRejection("ouch") }
    }
  }

  "The 'setCookie' directive" should {
    "add a respective Set-Cookie headers to successful responses" in pendingUntilFixed {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com")) { completeOk }
      } ~> check {
        status mustEqual OK
        header[`Set-Cookie`] mustEqual Some(`Set-Cookie`(HttpCookie("myCookie", "test.com")))
      }
    }

    "support setting multiple cookies at a time" in pendingUntilFixed {
      Get() ~> {
        setCookie(HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com")) { completeOk }
      } ~> check {
        status mustEqual OK
        headers.collect { case `Set-Cookie`(x) ⇒ x } mustEqual List(
          HttpCookie("myCookie", "test.com"), HttpCookie("myCookie2", "foobar.com"))
      }
    }
  }
}
