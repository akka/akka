/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._

class ExecutionDirectivesExamplesSpec extends RoutingSpec {
  "handleExceptions" in {
    val divByZeroHandler = ExceptionHandler {
      case _: ArithmeticException => complete(StatusCodes.BadRequest, "You've got your arithmetic wrong, fool!")
    }
    val route =
      path("divide" / IntNumber / IntNumber) { (a, b) =>
        handleExceptions(divByZeroHandler) {
          complete(s"The result is ${a / b}")
        }
      }

    Get("/divide/10/5") ~> route ~> check {
      responseAs[String] shouldEqual "The result is 2"
    }
    Get("/divide/10/0") ~> route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] shouldEqual "You've got your arithmetic wrong, fool!"
    }
  }
  "handleRejections" in {
    val totallyMissingHandler = RejectionHandler.newBuilder()
      .handleNotFound { complete(StatusCodes.NotFound, "Oh man, what you are looking for is long gone.") }
      .result()
    val route =
      pathPrefix("handled") {
        handleRejections(totallyMissingHandler) {
          path("existing")(complete("This path exists"))
        }
      }

    Get("/handled/existing") ~> route ~> check {
      responseAs[String] shouldEqual "This path exists"
    }
    Get("/missing") ~> Route.seal(route) /* applies default handler */ ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "The requested resource could not be found."
    }
    Get("/handled/missing") ~> route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] shouldEqual "Oh man, what you are looking for is long gone."
    }
  }
}
