/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import java.util.concurrent.TimeUnit

import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.http.scaladsl.server.{ CircuitBreakerOpenRejection, ExceptionHandler, Route }
import akka.util.Timeout
import akka.http.scaladsl.model._
import StatusCodes._
import akka.pattern.CircuitBreaker

// format: OFF

class FutureDirectivesExamplesSpec extends RoutingSpec {
  object TestException extends Throwable

  implicit val myExceptionHandler =
    ExceptionHandler {
      case TestException => ctx =>
        ctx.complete((InternalServerError, "Unsuccessful future!"))
    }

  implicit val responseTimeout = Timeout(2, TimeUnit.SECONDS)

  "onComplete" in {
    def divide(a: Int, b: Int): Future[Int] = Future {
      a / b
    }

    val route =
      path("divide" / IntNumber / IntNumber) { (a, b) =>
        onComplete(divide(a, b)) {
          case Success(value) => complete(s"The result was $value")
          case Failure(ex)    => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
        }
      }

    // tests:
    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }

    Get("/divide/10/0") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "An error occurred: / by zero"
    }
  }

  "onCompleteWithBreaker" in {
    def divide(a: Int, b: Int): Future[Int] = Future {
      a / b
    }

    val resetTimeout = 1.second
    val breaker = new CircuitBreaker(system.scheduler,
      maxFailures = 1,
      callTimeout = 5.seconds,
      resetTimeout
    )

    val route =
      path("divide" / IntNumber / IntNumber) { (a, b) =>
        onCompleteWithBreaker(breaker)(divide(a, b)) {
          case Success(value) => complete(s"The result was $value")
          case Failure(ex)    => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
        }
      }

    // tests:
    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }

    Get("/divide/10/0") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "An error occurred: / by zero"
    } // opens the circuit breaker

    Get("/divide/10/2") ~> route ~> check {
      rejection shouldBe a[CircuitBreakerOpenRejection]
    }

    Thread.sleep(resetTimeout.toMillis + 200)

    Get("/divide/10/2") ~> route ~> check {
      responseAs[String] shouldEqual "The result was 5"
    }
  }

  "onSuccess" in {
    val route =
      path("success") {
        onSuccess(Future { "Ok" }) { extraction =>
          complete(extraction)
        }
      } ~
      path("failure") {
        onSuccess(Future.failed[String](TestException)) { extraction =>
          complete(extraction)
        }
      }

    // tests:
    Get("/success") ~> route ~> check {
      responseAs[String] shouldEqual "Ok"
    }

    Get("/failure") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "Unsuccessful future!"
    }
  }

  "completeOrRecoverWith" in {
    val route =
      path("success") {
        completeOrRecoverWith(Future { "Ok" }) { extraction =>
          failWith(extraction) // not executed.
        }
      } ~
      path("failure") {
        completeOrRecoverWith(Future.failed[String](TestException)) { extraction =>
          failWith(extraction)
        }
      }

    // tests:
    Get("/success") ~> route ~> check {
      responseAs[String] shouldEqual "Ok"
    }

    Get("/failure") ~> Route.seal(route) ~> check {
      status shouldEqual InternalServerError
      responseAs[String] shouldEqual "Unsuccessful future!"
    }
  }
}
