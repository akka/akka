/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model

import java.util.Optional
import javax.net.ssl.{ SSLParameters, SSLContext }

import akka.http.javadsl.model.headers.Cookie
import akka.http.scaladsl.model
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.stream.TLSClientAuth
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
      val orderId = Query.create("orderId=123")
      Uri.create("/order").query(JavaApiTestCases.addSessionId(orderId)) must be(Uri.create("/order?orderId=123&session=abcdefghijkl"))
    }
    "create HttpsContext" in {
      akka.http.javadsl.ConnectionContext.https(
        SSLContext.getDefault,
        Optional.empty[java.util.Collection[String]],
        Optional.empty[java.util.Collection[String]],
        Optional.empty[TLSClientAuth],
        Optional.empty[SSLParameters]) mustNot be(null)
    }
  }
}