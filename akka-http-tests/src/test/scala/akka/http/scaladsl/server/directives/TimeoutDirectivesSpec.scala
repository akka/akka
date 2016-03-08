/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.IntegrationRoutingSpec

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class TimeoutDirectivesSpec extends IntegrationRoutingSpec {

  "Request Timeout" should {
    "be configurable in routing layer" in {

      val route = path("timeout") {
        withRequestTimeout(3.seconds) {
          val response: Future[String] = slowFuture() // very slow
          complete(response)
        }
      }

      Get("/timeout") ~!> route ~!> { response ⇒
        import response._

        status should ===(StatusCodes.ServiceUnavailable)
      }
    }
  }

  "allow mapping the response" in {
    val timeoutResponse = HttpResponse(StatusCodes.EnhanceYourCalm,
      entity = "Unable to serve response within time limit, please enchance your calm.")

    val route =
      path("timeout") {
        withRequestTimeout(500.millis) {
          withRequestTimeoutResponse(request ⇒ timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }
      } ~
        path("equivalent") {
          // updates timeout and handler at
          withRequestTimeout(500.millis, request ⇒ timeoutResponse) {
            val response: Future[String] = slowFuture() // very slow
            complete(response)
          }
        }

    Get("/timeout") ~!> route ~!> { response ⇒
      import response._
      status should ===(StatusCodes.EnhanceYourCalm)
    }
  }

  def slowFuture(): Future[String] = Promise[String].future

}
