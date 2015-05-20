/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import spray.http.{ RequestProcessingException, HttpResponse, StatusCodes }
import spray.routing.ValidationRejection

class RouteDirectivesExamplesSpec extends RoutingSpec {

  "complete-examples" in {
    val route =
      path("a") {
        complete(HttpResponse(entity = "foo"))
      } ~
        path("b") {
          complete(StatusCodes.Created, "bar")
        } ~
        (path("c") & complete("baz")) // `&` also works with `complete` as the 2nd argument

    Get("/a") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "foo"
    }

    Get("/b") ~> route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[String] shouldEqual "bar"
    }

    Get("/c") ~> route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "baz"
    }
  }

  "reject-examples" in {
    val route =
      path("a") {
        reject // don't handle here, continue on
      } ~
        path("a") {
          complete("foo")
        } ~
        path("b") {
          // trigger a ValidationRejection explicitly
          // rather than through the `validate` directive
          reject(ValidationRejection("Restricted!"))
        }

    Get("/a") ~> route ~> check {
      responseAs[String] shouldEqual "foo"
    }

    Get("/b") ~> route ~> check {
      rejection shouldEqual ValidationRejection("Restricted!")
    }
  }

  "redirect-examples" in {
    val route =
      pathPrefix("foo") {
        pathSingleSlash {
          complete("yes")
        } ~
          pathEnd {
            redirect("/foo/", StatusCodes.PermanentRedirect)
          }
      }

    Get("/foo/") ~> route ~> check {
      responseAs[String] shouldEqual "yes"
    }

    Get("/foo") ~> route ~> check {
      status shouldEqual StatusCodes.PermanentRedirect
      responseAs[String] shouldEqual """The request, and all future requests should be repeated using <a href="/foo/">this URI</a>."""
    }
  }

  "failwith-examples" in {
    val route =
      path("foo") {
        failWith(new RequestProcessingException(StatusCodes.BandwidthLimitExceeded))
      }

    Get("/foo") ~> Route.seal(route) ~> check {
      status shouldEqual StatusCodes.BandwidthLimitExceeded
      responseAs[String] shouldEqual "Bandwidth limit has been exceeded."
    }
  }

}
