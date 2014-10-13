/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.directives

import akka.http.model._
import MediaTypes._
import headers._
import StatusCodes._

import akka.http.server._

class RespondWithDirectivesSpec extends RoutingSpec {

  "respondWithStatus" should {
    "set the given status on successful responses" in {
      Get() ~> {
        respondWithStatus(Created) { completeOk }
      } ~> check { response shouldEqual HttpResponse(Created) }
    }
    "leave rejections unaffected" in {
      Get() ~> {
        respondWithStatus(Created) { reject }
      } ~> check { rejections shouldEqual Nil }
    }
  }

  "respondWithHeader" should {
    val customHeader = RawHeader("custom", "custom")
    "add the given headers to successful responses" in {
      Get() ~> {
        respondWithHeader(customHeader) { completeOk }
      } ~> check { response shouldEqual HttpResponse(headers = customHeader :: Nil) }
    }
    "leave rejections unaffected" in {
      Get() ~> {
        respondWithHeader(customHeader) { reject }
      } ~> check { rejections shouldEqual Nil }
    }
  }

  "The 'respondWithMediaType' directive" should {

    "override the media-type of its inner route response" in {
      Get() ~> {
        respondWithMediaType(`text/html`) {
          complete("yeah")
        }
      } ~> check { mediaType shouldEqual `text/html` }
    }

    "disable content-negotiation for its inner marshaller" in {
      Get() ~> addHeader(Accept(`text/css`)) ~> {
        respondWithMediaType(`text/css`) {
          complete("yeah")
        }
      } ~> check { mediaType shouldEqual `text/css` }
    }

    "reject an unacceptable request" in {
      Get() ~> addHeader(Accept(`text/css`)) ~> {
        respondWithMediaType(`text/xml`) {
          complete("yeah")
        }
      } ~> check { rejection shouldEqual UnacceptedResponseContentTypeRejection(List(ContentType(`text/xml`))) }
    }
  }

}
