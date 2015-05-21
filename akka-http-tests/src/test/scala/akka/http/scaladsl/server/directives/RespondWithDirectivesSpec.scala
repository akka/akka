/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import MediaTypes._
import headers._
import StatusCodes._

import akka.http.scaladsl.server._

class RespondWithDirectivesSpec extends RoutingSpec {

  "overrideStatusCode" should {
    "set the given status on successful responses" in {
      Get() ~> {
        overrideStatusCode(Created) { completeOk }
      } ~> check { response shouldEqual HttpResponse(Created) }
    }
    "leave rejections unaffected" in {
      Get() ~> {
        overrideStatusCode(Created) { reject }
      } ~> check { rejections shouldEqual Nil }
    }
  }

  val customHeader = RawHeader("custom", "custom")
  val customHeader2 = RawHeader("custom2", "custom2")
  val existingHeader = RawHeader("custom", "existing")

  "respondWithHeader" should {
    val customHeader = RawHeader("custom", "custom")
    "add the given header to successful responses" in {
      Get() ~> {
        respondWithHeader(customHeader) { completeOk }
      } ~> check { response shouldEqual HttpResponse(headers = customHeader :: Nil) }
    }
  }
  "respondWithHeaders" should {
    "add the given headers to successful responses" in {
      Get() ~> {
        respondWithHeaders(customHeader, customHeader2) { completeOk }
      } ~> check { response shouldEqual HttpResponse(headers = customHeader :: customHeader2 :: Nil) }
    }
  }
  "respondWithDefaultHeader" should {
    def route(extraHeaders: HttpHeader*) = respondWithDefaultHeader(customHeader) {
      respondWithHeaders(extraHeaders: _*) {
        completeOk
      }
    }

    "add the given header to a response if the header was missing before" in {
      Get() ~> route() ~> check { response shouldEqual HttpResponse(headers = customHeader :: Nil) }
    }
    "not change a response if the header already existed" in {
      Get() ~> route(existingHeader) ~> check { response shouldEqual HttpResponse(headers = existingHeader :: Nil) }
    }
  }
  "respondWithDefaultHeaders" should {
    def route(extraHeaders: HttpHeader*) = respondWithDefaultHeaders(customHeader, customHeader2) {
      respondWithHeaders(extraHeaders: _*) {
        completeOk
      }
    }

    "add the given headers to a response if the header was missing before" in {
      Get() ~> route() ~> check { response shouldEqual HttpResponse(headers = customHeader :: customHeader2 :: Nil) }
    }
    "not update an existing header" in {
      Get() ~> route(existingHeader) ~> check {
        response shouldEqual HttpResponse(headers = List(customHeader2, existingHeader))
      }
    }
  }
}
