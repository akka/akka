/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import java.util.concurrent.atomic.AtomicInteger

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.{ CircuitBreakerOpenRejection, RequestContext, RoutingSpec }
import akka.pattern.CircuitBreaker
import org.scalatest.Inside

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class ReverseProxyDirectivesSpec extends RoutingSpec with Inside {

  trait TestWithCircuitBreaker {
    val breakerResetTimeout = 500.millis
    val breaker = new CircuitBreaker(system.scheduler, maxFailures = 1, callTimeout = 10.seconds, breakerResetTimeout)

    def openBreaker() = breaker.withCircuitBreaker(Future.failed(new Exception("boom")))
  }

  def completeTryResponse(tryHttpResponse: Try[HttpResponse]) = { ctx: RequestContext ⇒
    tryHttpResponse match {
      case Success(response) ⇒ Future.successful(Complete(response))
      case Failure(e)        ⇒ Future.failed(e)
    }
  }

  "The 'forwardRequest' directive" should {
    "intercept the incoming HttpRequest" in {
      val i: AtomicInteger = new AtomicInteger(0)
      val route = forwardRequest(x ⇒ Future.successful(Ok.copy(headers = x.headers, entity = x.entity)), request ⇒ { i.incrementAndGet(); request.withEntity("executed") }) { completeTryResponse(_) }
      Get() ~> route ~> check {
        i.get() shouldEqual 1
        responseAs[String] shouldEqual "executed"
      }
      Get() ~> route ~> check {
        i.get() shouldEqual 2
        responseAs[String] shouldEqual "executed"
      }
    }
    "use the provided request executor" in {
      val i: AtomicInteger = new AtomicInteger(0)
      val route = forwardRequest(x ⇒ { i.incrementAndGet(); Future.successful(Ok.withEntity("executed")) }, identity(_)) { completeTryResponse(_) }
      Get() ~> route ~> check {
        i.get() shouldEqual 1
        responseAs[String] shouldEqual "executed"
      }
      Get() ~> route ~> check {
        i.get() shouldEqual 2
        responseAs[String] shouldEqual "executed"
      }
    }
    "respond with 500 Internal Server Error when proxing request fails" in {
      val route = forwardRequest(x ⇒ Future.failed(new RuntimeException("ouch!")), identity(_)) { completeTryResponse(_) }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "There was an internal server error."
        response.status.intValue shouldEqual 500
      }
    }
    "respond with proxies error when proxy encounters an error" in {
      val route = forwardRequest(_ ⇒ Future.successful(HttpResponse(StatusCodes.InternalServerError, entity = "damn")), identity(_)) { completeTryResponse(_) }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "damn"
        response.status.intValue shouldEqual 500
      }
    }
    "use the circuitBreaker if provided" in new TestWithCircuitBreaker {
      val route = forwardRequest(_ ⇒ Future.successful(Ok), identity(_), Some(breaker)) { completeTryResponse(_) }
      Get() ~> route ~> check {
        response.status.intValue shouldEqual 200
      }
      openBreaker()
      Get() ~> route ~> check {
        inside(rejection) {
          case CircuitBreakerOpenRejection(_) ⇒
        }
      }
      Thread.sleep(breakerResetTimeout.toMillis + 200)
      Get() ~> route ~> check {
        response.status.intValue shouldEqual 200
      }
    }

  }
}
