/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ ContentTypes, MediaRanges }
import akka.http.scaladsl.server.{ Route, RoutingSpec }

class ContentNegotiationGivenResponseCodeSpec extends RoutingSpec {

  val routes = {
    pathPrefix(Segment) { mode ⇒
      complete {
        mode match {
          case "200-text" ⇒ OK -> "ok"
          case "201-text" ⇒ Created -> "created"
          case "400-text" ⇒ BadRequest -> "bad-request"
        }
      }
    }
  }

  "Return NotAcceptable for" should {
    "200 OK response, when entity not available in Accept-ed MediaRange" in {
      val request = Post("/200-text").addHeader(Accept(MediaRanges.`application/*`))

      request ~> Route.seal(routes) ~> check {
        status should ===(NotAcceptable)
        entityAs[String] should include("text/plain")
      }
    }

    "201 Created response, when entity not available in Accept-ed MediaRange" in {
      val request = Post("/201-text").addHeader(Accept(MediaRanges.`application/*`))
      request ~> Route.seal(routes) ~> check {
        status should ===(NotAcceptable)
        entityAs[String] should include("text/plain")
      }
    }
  }

  "Allow not explicitly Accept-ed content type to be returned if response code is non-2xx" should {
    "400 BadRequest response, when entity not available in Accept-ed MediaRange" in {
      val request = Post("/400-text").addHeader(Accept(MediaRanges.`application/*`))
      request ~> Route.seal(routes) ~> check {
        status should ===(BadRequest)
        contentType should ===(ContentTypes.`text/plain(UTF-8)`)
        entityAs[String] should include("bad-request")
      }
    }
  }

}
