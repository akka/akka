/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model

import javax.net.ssl.SSLContext

import akka.http.javadsl.model.headers.Cookie
import akka.http.scaladsl.model
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import org.scalatest.{ FreeSpec, MustMatchers }

import scala.collection.immutable

class JavaApiTestCaseSpecs extends FreeSpec with MustMatchers {
  "JavaApiTestCases should work as intended" - {
    "buildRequest" in {
      JavaApiTestCases.buildRequest() must be(
        model.HttpRequest(
          model.HttpMethods.POST,
          uri = "/send"))
    }
    "handleRequest" - {
      "wrong method" in {
        JavaApiTestCases.handleRequest(model.HttpRequest(model.HttpMethods.HEAD)) must be(
          model.HttpResponse(model.StatusCodes.MethodNotAllowed, entity = "Unsupported method"))
      }
      "missing path" in {
        JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/blubber")) must be(
          model.HttpResponse(model.StatusCodes.NotFound, entity = "Not found"))
      }
      "happy path" - {
        "with name parameter" in {
          JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/hello?name=Peter")) must be(
            model.HttpResponse(entity = "Hello Peter!"))
        }
        "without name parameter" in {
          JavaApiTestCases.handleRequest(model.HttpRequest(uri = "/hello")) must be(
            model.HttpResponse(entity = "Hello Mister X!"))
        }
      }
    }
    "addAuthentication" in {
      JavaApiTestCases.addAuthentication(model.HttpRequest()) must be(
        model.HttpRequest(headers = immutable.Seq(model.headers.Authorization(BasicHttpCredentials("username", "password")))))
    }
    "removeCookies" in {
      val testRequest = model.HttpRequest(headers = immutable.Seq(Cookie.create("test", "blub")))
      JavaApiTestCases.removeCookies(testRequest) must be(
        model.HttpRequest())
    }
    "createUriForOrder" in {
      JavaApiTestCases.createUriForOrder("123", "149", "42") must be(
        Uri.create("/order?orderId=123&price=149&amount=42"))
    }
    "addSessionId" in {
      val origin = Uri.create("/order?orderId=123")
      JavaApiTestCases.addSessionId(origin) must be(Uri.create("/order?orderId=123&session=abcdefghijkl"))
    }
    "create HttpsContext" in {
      import akka.japi.{ Option ⇒ JOption }
      akka.http.javadsl.HttpsContext.create(SSLContext.getDefault,
        JOption.none,
        JOption.none,
        JOption.none,
        JOption.none) mustNot be(null)
    }
  }
}