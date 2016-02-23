/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.{ HttpHeader, StatusCodes }
import akka.http.scaladsl.model.headers._

import scala.concurrent.Future
import scala.util.{ Success, Failure, Try }

object ModeledCustomHeaderSpec {

  //#modeled-api-key-custom-header
  object ApiTokenHeader extends ModeledCustomHeaderCompanion[ApiTokenHeader] {
    def renderInRequests = false
    def renderInResponses = false
    override val name = "apiKey"
    override def parse(value: String) = Try(new ApiTokenHeader(value))
  }
  final class ApiTokenHeader(token: String) extends ModeledCustomHeader[ApiTokenHeader] {
    def renderInRequests = false
    def renderInResponses = false
    override val companion = ApiTokenHeader
    override def value: String = token
  }
  //#modeled-api-key-custom-header

  object DifferentHeader extends ModeledCustomHeaderCompanion[DifferentHeader] {
    def renderInRequests = false
    def renderInResponses = false
    override val name = "different"
    override def parse(value: String) =
      if (value contains " ") Failure(new Exception("Contains illegal whitespace!"))
      else Success(new DifferentHeader(value))
  }
  final class DifferentHeader(token: String) extends ModeledCustomHeader[DifferentHeader] {
    def renderInRequests = false
    def renderInResponses = false
    override val companion = DifferentHeader
    override def value = token
  }

}

class ModeledCustomHeaderSpec extends RoutingSpec {
  import ModeledCustomHeaderSpec._

  "CustomHeader" should {

    "be able to be extracted using expected syntax" in {
      //#matching-examples
      val ApiTokenHeader(t1) = ApiTokenHeader("token")
      t1 should ===("token")

      val RawHeader(k2, v2) = ApiTokenHeader("token")
      k2 should ===("apiKey")
      v2 should ===("token")

      // will match, header keys are case insensitive
      val ApiTokenHeader(v3) = RawHeader("APIKEY", "token")
      v3 should ===("token")

      intercept[MatchError] {
        // won't match, different header name
        val ApiTokenHeader(v4) = DifferentHeader("token")
      }

      intercept[MatchError] {
        // won't match, different header name
        val RawHeader("something", v5) = DifferentHeader("token")
      }

      intercept[MatchError] {
        // won't match, different header name
        val ApiTokenHeader(v6) = RawHeader("different", "token")
      }
      //#matching-examples
    }

    "be able to match from RawHeader" in {

      //#matching-in-routes
      def extractFromCustomHeader = headerValuePF {
        case t @ ApiTokenHeader(token) ⇒ s"extracted> $t"
        case raw: RawHeader            ⇒ s"raw> $raw"
      }

      val routes = extractFromCustomHeader { s ⇒
        complete(s)
      }

      Get().withHeaders(RawHeader("apiKey", "TheKey")) ~> routes ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] should ===("extracted> apiKey: TheKey")
      }

      Get().withHeaders(RawHeader("somethingElse", "TheKey")) ~> routes ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] should ===("raw> somethingElse: TheKey")
      }

      Get().withHeaders(ApiTokenHeader("TheKey")) ~> routes ~> check {
        status should ===(StatusCodes.OK)
        responseAs[String] should ===("extracted> apiKey: TheKey")
      }
      //#matching-in-routes
    }

    "fail with useful message when unable to parse" in {
      val ex = intercept[Exception] {
        DifferentHeader("Hello world") // illegal " "
      }

      ex.getMessage should ===("Unable to construct custom header by parsing: 'Hello world'")
      ex.getCause.getMessage should include("whitespace")
    }
  }

}
