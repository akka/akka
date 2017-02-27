/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpEncodings._
import akka.http.scaladsl.model.headers._
import scala.concurrent.Future
import akka.testkit.EventFilter
import org.scalatest.matchers.Matcher

class ExecutionDirectivesSpec extends RoutingSpec {
  object MyException extends RuntimeException("Boom")
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
    "not interfere with alternative routes" in EventFilter[MyException.type](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("Boom")
    ).intercept {
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
    "not handle other exceptions" in EventFilter[RuntimeException](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("buh")
    ).intercept {
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
    "always fall back to a default content type" in EventFilter[RuntimeException](
      occurrences = 2,
      message = BasicRouteSpecs.defaultExnHandler500Error("buh2")
    ).intercept {
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

  "The `handleRejections` directive" should {
    "handle encodeResponse inside RejectionHandler for non-success responses" in {
      val rejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
        .handleNotFound {
          encodeResponseWith(Gzip) {
            complete((404, "Not here!"))
          }
        }.result()

      Get("/hell0") ~>
        get {
          handleRejections(rejectionHandler) {
            encodeResponseWith(Gzip) {
              path("hello") {
                get {
                  complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, "world"))
                }
              }
            }
          }
        } ~> check {
          response should haveContentEncoding(gzip)
          status shouldEqual StatusCodes.NotFound
        }
    }
  }

  "Default handler" should {
    "handle `IllegalRequestException` with appropriate block of `ErrorHandler`" in EventFilter.warning(
      occurrences = 1,
      message = "Illegal request: 'Some summary.'. Completing with 409 Conflict response."
    ).intercept {
      Get("/abc") ~>
        get {
          throw new IllegalRequestException(ErrorInfo(summary = "Some summary."), StatusCodes.Conflict)
        } ~> check {
          status shouldEqual StatusCodes.Conflict
        }
    }

    "handle exceptions other than `IllegalRequestException` with appropriate block of `ErrorHandler`" in EventFilter[RuntimeException](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error("re")
    ).intercept {
        Get("/abc") ~>
          get {
            throw new RuntimeException("re")
          }
      } ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }

    "show exception class if .getMessage == null" in EventFilter[NullPointerException](
      occurrences = 1,
      message = BasicRouteSpecs.defaultExnHandler500Error(
        s"${classOf[NullPointerException].getName} (No error message supplied)"
      )
    ).intercept {
        Get("/abc") ~> get {
          throw new NullPointerException
        } ~> check {
          status shouldEqual StatusCodes.InternalServerError
        }
      }
  }

  def exceptionShouldBeHandled(route: Route) =
    Get("/abc") ~> route ~> check {
      status shouldEqual StatusCodes.InternalServerError
      responseAs[String] shouldEqual "Pling! Plong! Something went wrong!!!"
    }

  def haveContentEncoding(encoding: HttpEncoding): Matcher[HttpResponse] =
    be(Some(`Content-Encoding`(encoding))) compose { (_: HttpResponse).header[`Content-Encoding`] }
}
