/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.{ MediaTypes, MediaRanges, StatusCodes }
import akka.http.scaladsl.model.headers._
import scala.concurrent.Future
import akka.testkit.EventFilter

class ExecutionDirectivesSpec extends RoutingSpec {
  object MyException extends RuntimeException
  val handler =
    ExceptionHandler {
      case MyException ⇒ complete((500, "Pling! Plong! Something went wrong!!!"))
    }

  "The `handleExceptions` directive" should {
    "handle an exception strictly thrown in the inner route with the supplied exception handler" in {
      exceptionShouldBeHandled {
        handleExceptions(handler) { ctx ⇒
          throw MyException
        }
      }
    }
    "handle an Future.failed RouteResult with the supplied exception handler" in {
      exceptionShouldBeHandled {
        handleExceptions(handler) { ctx ⇒
          Future.failed(MyException)
        }
      }
    }
    "handle an eventually failed Future[RouteResult] with the supplied exception handler" in {
      exceptionShouldBeHandled {
        handleExceptions(handler) { ctx ⇒
          Future {
            Thread.sleep(100)
            throw MyException
          }
        }
      }
    }
    "handle an exception happening during route building" in {
      exceptionShouldBeHandled {
        get {
          handleExceptions(handler) {
            throw MyException
          }
        }
      }
    }
    "not interfere with alternative routes" in EventFilter[MyException.type](occurrences = 1).intercept {
      Get("/abc") ~>
        get {
          handleExceptions(handler)(reject) ~ { ctx ⇒
            throw MyException
          }
        } ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "There was an internal server error."
        }
    }
    "not handle other exceptions" in EventFilter[RuntimeException](occurrences = 1, message = "buh").intercept {
      Get("/abc") ~>
        get {
          handleExceptions(handler) {
            throw new RuntimeException("buh")
          }
        } ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "There was an internal server error."
        }
    }
    "always fall back to a default content type" in EventFilter[RuntimeException](occurrences = 2, message = "buh2").intercept {
      Get("/abc") ~> Accept(MediaTypes.`application/json`) ~>
        get {
          handleExceptions(handler) {
            throw new RuntimeException("buh2")
          }
        } ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "There was an internal server error."
        }

      Get("/abc") ~> Accept(MediaTypes.`text/xml`, MediaRanges.`*/*`.withQValue(0f)) ~>
        get {
          handleExceptions(handler) {
            throw new RuntimeException("buh2")
          }
        } ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "There was an internal server error."
        }
    }
  }

  def exceptionShouldBeHandled(route: Route) =
    Get("/abc") ~> route ~> check {
      status shouldEqual StatusCodes.InternalServerError
      responseAs[String] shouldEqual "Pling! Plong! Something went wrong!!!"
    }
}
