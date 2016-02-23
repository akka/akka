/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ StatusCodes, HttpMethods }
import akka.http.scaladsl.server._

class MethodDirectivesSpec extends RoutingSpec {

  "get | put" should {
    lazy val getOrPut = (get | put) { completeOk }

    "block POST requests" in {
      Post() ~> getOrPut ~> check { handled shouldEqual false }
    }
    "let GET requests pass" in {
      Get() ~> getOrPut ~> check { response shouldEqual Ok }
    }
    "let PUT requests pass" in {
      Put() ~> getOrPut ~> check { response shouldEqual Ok }
    }
  }

  "two failed `get` directives" should {
    "only result in a single Rejection" in {
      Put() ~> {
        get { completeOk } ~
          get { completeOk }
      } ~> check {
        rejections shouldEqual List(MethodRejection(HttpMethods.GET))
      }
    }
  }

  "overrideMethodWithParameter" should {
    "change the request method" in {
      Get("/?_method=put") ~> overrideMethodWithParameter("_method") {
        get { complete("GET") } ~
          put { complete("PUT") }
      } ~> check { responseAs[String] shouldEqual "PUT" }
    }
    "not affect the request when not specified" in {
      Get() ~> overrideMethodWithParameter("_method") {
        get { complete("GET") } ~
          put { complete("PUT") }
      } ~> check { responseAs[String] shouldEqual "GET" }
    }
    "complete with 501 Not Implemented when not a valid method" in {
      Get("/?_method=hallo") ~> overrideMethodWithParameter("_method") {
        get { complete("GET") } ~
          put { complete("PUT") }
      } ~> check { status shouldEqual StatusCodes.NotImplemented }
    }
  }

  "MethodRejections under a successful match" should {
    "be cancelled if the match happens after the rejection" in {
      Put() ~> {
        get { completeOk } ~
          put { reject(RequestEntityExpectedRejection) }
      } ~> check {
        rejections shouldEqual List(RequestEntityExpectedRejection)
      }
    }
    "be cancelled if the match happens after the rejection (example 2)" in {
      Put() ~> {
        (get & complete(Ok)) ~ (put & reject(RequestEntityExpectedRejection))
      } ~> check {
        rejections shouldEqual List(RequestEntityExpectedRejection)
      }
    }
    "be cancelled if the match happens before the rejection" in {
      Put() ~> {
        put { reject(RequestEntityExpectedRejection) } ~ get { completeOk }
      } ~> check {
        rejections shouldEqual List(RequestEntityExpectedRejection)
      }
    }
    "be cancelled if the match happens before the rejection (example 2)" in {
      Put() ~> {
        (put & reject(RequestEntityExpectedRejection)) ~ (get & complete(Ok))
      } ~> check {
        rejections shouldEqual List(RequestEntityExpectedRejection)
      }
    }
  }
}