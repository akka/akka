/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.http.scaladsl.server.directives

import akka.http.caching.scaladsl.Cache
import akka.http.impl.util.SingletonException
import akka.http.scaladsl.model.{ HttpResponse, Uri }
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ ExceptionHandler, RequestContext, RouteResult }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.model.HttpMethods.GET
import org.scalatest.{ Matchers, WordSpec }

class CachingDirectivesSpec extends WordSpec with Matchers with ScalatestRouteTest with CachingDirectives {

  val simpleKeyer: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext if r.request.method == GET ⇒ r.request.uri
  }

  val countingService = {
    var i = 0
    cache(routeCache, simpleKeyer) {
      complete {
        i += 1
        i.toString
      }
    }
  }
  val errorService = {
    var i = 0
    cache(routeCache, simpleKeyer) {
      complete {
        i += 1
        HttpResponse(500 + i)
      }
    }
  }

  "the cache directive" should {
    "return and cache the response of the first GET" in {
      Get() ~> countingService ~> check { responseAs[String] shouldEqual "1" }
    }
    "return the cached response for a second GET" in {
      Get() ~> countingService ~> check { responseAs[String] shouldEqual "1" }
    }
    "return the cached response also for HttpFailures on GETs" in {
      Get() ~> errorService ~> check { response shouldEqual HttpResponse(501) }
    }
    "not cache responses for PUTs" in {
      Put() ~> countingService ~> check { responseAs[String] shouldEqual "2" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: no-cache` header" in {
      Get() ~> addHeader(`Cache-Control`(`no-cache`)) ~> countingService ~> check { responseAs[String] shouldEqual "3" }
    }
    "not cache responses for GETs if the request contains a `Cache-Control: max-age=0` header" in {
      Get() ~> addHeader(`Cache-Control`(`max-age`(0))) ~> countingService ~> check { responseAs[String] shouldEqual "4" }
    }

    "be transparent to exceptions thrown from its inner route" in {
      case object MyException extends SingletonException
      implicit val myExceptionHandler = ExceptionHandler {
        case MyException ⇒ complete("Good")
      }

      Get() ~> cache(routeCache, simpleKeyer) {
        _ ⇒ throw MyException // thrown directly
      } ~> check { responseAs[String] shouldEqual "Good" }

      Get() ~> cache(routeCache, simpleKeyer) {
        _.fail(MyException) // bubbling up
      } ~> check { responseAs[String] shouldEqual "Good" }
    }
  }

}
