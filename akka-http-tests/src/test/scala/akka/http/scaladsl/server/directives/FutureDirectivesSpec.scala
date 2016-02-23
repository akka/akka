/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.StatusCodes
import scala.concurrent.Future
import akka.testkit.EventFilter

class FutureDirectivesSpec extends RoutingSpec {

  class TestException(msg: String) extends Exception(msg)
  object TestException extends Exception("XXX")
  def throwTestException[T](msgPrefix: String): T ⇒ Nothing = t ⇒ throw new TestException(msgPrefix + t)

  implicit val exceptionHandler = ExceptionHandler {
    case e: TestException ⇒ complete((StatusCodes.InternalServerError, "Oops. " + e))
  }

  "The `onComplete` directive" should {
    "unwrap a Future in the success case" in {
      var i = 0
      def nextNumber() = { i += 1; i }
      val route = onComplete(Future.successful(nextNumber())) { echoComplete }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(1)"
      }
      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Success(2)"
      }
    }
    "unwrap a Future in the failure case" in {
      Get() ~> onComplete(Future.failed[String](new RuntimeException("no"))) { echoComplete } ~> check {
        responseAs[String] shouldEqual "Failure(java.lang.RuntimeException: no)"
      }
    }
    "catch an exception in the success case" in {
      Get() ~> onComplete(Future.successful("ok")) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Success(ok)"
      }
    }
    "catch an exception in the failure case" in {
      Get() ~> onComplete(Future.failed[String](new RuntimeException("no"))) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when Failure(java.lang.RuntimeException: no)"
      }
    }
  }

  "The `onSuccess` directive" should {
    "unwrap a Future in the success case" in {
      Get() ~> onSuccess(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] shouldEqual "yes"
      }
    }
    "propagate the exception in the failure case" in EventFilter[Exception](occurrences = 1, message = "XXX").intercept {
      Get() ~> onSuccess(Future.failed(TestException)) { echoComplete } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }
    "catch an exception in the success case" in {
      Get() ~> onSuccess(Future.successful("ok")) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when ok"
      }
    }
    "catch an exception in the failure case" in EventFilter[Exception](occurrences = 1, message = "XXX").intercept {
      Get() ~> onSuccess(Future.failed(TestException)) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual "There was an internal server error."
      }
    }
  }

  "The `completeOrRecoverWith` directive" should {
    "complete the request with the Future's value if the future succeeds" in {
      Get() ~> completeOrRecoverWith(Future.successful("yes")) { echoComplete } ~> check {
        responseAs[String] shouldEqual "yes"
      }
    }
    "don't call the inner route if the Future succeeds" in {
      Get() ~> completeOrRecoverWith(Future.successful("ok")) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "ok"
      }
    }
    "recover using the inner route if the Future fails" in {
      val route = completeOrRecoverWith(Future.failed[String](TestException)) {
        case e ⇒ complete(s"Exception occurred: ${e.getMessage}")
      }

      Get() ~> route ~> check {
        responseAs[String] shouldEqual "Exception occurred: XXX"
      }
    }
    "catch an exception during recovery" in {
      Get() ~> completeOrRecoverWith(Future.failed[String](TestException)) { throwTestException("EX when ") } ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual s"Oops. akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException: EX when akka.http.scaladsl.server.directives.FutureDirectivesSpec$$TestException$$: XXX"
      }
    }
  }
}
