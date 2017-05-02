/*
 * Copyright (C) 2009-2017 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ ContentTypes, MediaRanges, MediaTypes }
import akka.http.scaladsl.server.{ Route, RoutingSpec }

class FromStatusCodeAndXYZMarshallerSpec extends RoutingSpec {
  case class ErrorInfo(errorMessage: String)
  // a somewhat arbitrary ErrorInfo marshaller that can either return a text or an application/json response
  implicit val errorInfoMarshaller: ToEntityMarshaller[ErrorInfo] = {
    import spray.json.DefaultJsonProtocol._
    implicit val errorInfoFormat = jsonFormat1(ErrorInfo.apply _)
    Marshaller.oneOf(
      Marshaller.StringMarshaller.compose[ErrorInfo](_.errorMessage),
      SprayJsonSupport.sprayJsonMarshaller(errorInfoFormat)
    )
  }

  val routes =
    path("200-text")(complete(OK → "ok")) ~
      path("201-text")(complete(Created → "created")) ~
      path("400-text")(complete(BadRequest → "bad-request")) ~
      path("400-error-info")(complete(BadRequest → ErrorInfo("This request was really bad. Try again.")))

  "The fromStatusCodeAndXYZ marshaller" should {
    "for 200 OK response + text" should {
      "return text when text/plain is Accept-ed" in {
        val request = Post("/200-text").addHeader(Accept(MediaRanges.`text/*`))

        request ~> Route.seal(routes) ~> check {
          status should ===(OK)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should ===("ok")
        }
      }
      "return 406 NotAcceptable when text/plain is not Accept-ed" in {
        val request = Post("/200-text").addHeader(Accept(MediaRanges.`application/*`))

        request ~> Route.seal(routes) ~> check {
          status should ===(NotAcceptable)
          entityAs[String] should include("text/plain")
        }
      }
    }
    "for 201 Created response + text" should {
      "return text when text/plain is Accept-ed" in {
        val request = Post("/201-text").addHeader(Accept(MediaRanges.`text/*`))

        request ~> Route.seal(routes) ~> check {
          status should ===(Created)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should ===("created")
        }
      }
      "return 406 NotAcceptable when text/plain is not Accept-ed" in {
        val request = Post("/201-text").addHeader(Accept(MediaRanges.`application/*`))

        request ~> Route.seal(routes) ~> check {
          status should ===(NotAcceptable)
          entityAs[String] should include("text/plain")
        }
      }
    }
    "for 400 BadRequest response + text" should {
      "return text when text/plain is Accept-ed" in {
        val request = Post("/400-text").addHeader(Accept(MediaRanges.`text/*`))
        request ~> Route.seal(routes) ~> check {
          status should ===(BadRequest)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should ===("bad-request")
        }
      }
      "allow not explicitly Accept-ed content type to be returned if response code is non-2xx for" in {
        val request = Post("/400-text").addHeader(Accept(MediaRanges.`application/*`))
        request ~> Route.seal(routes) ~> check {
          status should ===(BadRequest)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should include("bad-request")
        }
      }
    }

    "for 400 BadRequest + ErrorInfo (with custom oneOf marshaller that supports text/plain and application/json)" should {
      "return text/plain response if Accept-ed" in {
        val request = Post("/400-error-info").addHeader(Accept(MediaRanges.`text/*`))
        request ~> Route.seal(routes) ~> check {
          status should ===(BadRequest)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should ===("This request was really bad. Try again.")
        }
      }
      "return application/json response if Accept-ed" in {
        val request = Post("/400-error-info").addHeader(Accept(MediaTypes.`application/json`))
        request ~> Route.seal(routes) ~> check {
          status should ===(BadRequest)
          contentType should ===(ContentTypes.`application/json`)
          entityAs[String] should ===("""{"errorMessage":"This request was really bad. Try again."}""")
        }
      }
      "return text/plain response if none of the Accept-ed ranges matches" in {
        val request = Post("/400-error-info").addHeader(Accept(MediaRanges.`audio/*`))
        request ~> Route.seal(routes) ~> check {
          status should ===(BadRequest)
          contentType should ===(ContentTypes.`text/plain(UTF-8)`)
          entityAs[String] should ===("This request was really bad. Try again.")
        }
      }
    }
  }

}
