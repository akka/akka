/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.RoutingSpec
import docs.CompileOnlySpec

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class TimeoutDirectivesExamplesSpec extends RoutingSpec with CompileOnlySpec {

  "Request Timeout" should {
    "be configurable in routing layer" in compileOnlySpec {
      //#withRequestTimeout-plain
      val route =
        path("timeout") {
          withRequestTimeout(3.seconds) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      //#
    }
    "without timeout" in compileOnlySpec {
      //#withoutRequestTimeout-1
      val route =
        path("timeout") {
          withoutRequestTimeout {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      //#
    }

    "allow mapping the response while setting the timeout" in compileOnlySpec {
      //#withRequestTimeout-with-handler
      val timeoutResponse = HttpResponse(StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enchance your calm.")

      val route =
        path("timeout") {
          // updates timeout and handler at
          withRequestTimeout(1.milli, request => timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      //#
    }

    "allow mapping the response" in compileOnlySpec {
      pending // compile only spec since requires actuall Http server to be run

      //#withRequestTimeoutResponse
      val timeoutResponse = HttpResponse(StatusCodes.EnhanceYourCalm,
        entity = "Unable to serve response within time limit, please enchance your calm.")

      val route =
        path("timeout") {
          withRequestTimeout(1.milli) {
            withRequestTimeoutResponse(request => timeoutResponse) {
              val response: Future[String] = slowFuture() // very slow
              complete(response)
            }
          }
        }
      //#
    }
  }

  def slowFuture(): Future[String] = Promise[String].future

}
