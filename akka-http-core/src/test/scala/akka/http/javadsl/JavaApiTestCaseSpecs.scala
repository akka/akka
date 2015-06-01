/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model

import akka.http.javadsl.model.headers.Cookie

import scala.collection.immutable
import org.scalatest.{ MustMatchers, FreeSpec }
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model

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
        Accessors.Uri(model.Uri("/order?orderId=123&price=149&amount=42")))
    }
    "addSessionId" in {
      val origin = Accessors.Uri("/order?orderId=123")
      JavaApiTestCases.addSessionId(origin) must be(Accessors.Uri("/order?orderId=123&session=abcdefghijkl"))
    }
  }
}