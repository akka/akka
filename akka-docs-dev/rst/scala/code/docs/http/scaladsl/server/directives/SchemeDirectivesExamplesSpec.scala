/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import akka.http.scaladsl.model._

class SchemeDirectivesExamplesSpec extends RoutingSpec {
  "example-1" in {
    val route =
      schemeName { scheme =>
        complete(s"The scheme is '${scheme}'")
      }

    Get("https://www.example.com/") ~> route ~> check {
      responseAs[String] shouldEqual "The scheme is 'https'"
    }
  }

  "example-2" in {
    val route =
      scheme("http") {
        extract(_.request.uri) { uri ⇒
          redirect(uri.copy(scheme = "https"), MovedPermanently)
        }
      } ~
        scheme("https") {
          complete(s"Safe and secure!")
        }

    Get("http://www.example.com/hello") ~> route ~> check {
      status shouldEqual MovedPermanently
      header[headers.Location] shouldEqual Some(headers.Location(Uri("https://www.example.com/hello")))
    }

    Get("https://www.example.com/hello") ~> route ~> check {
      responseAs[String] shouldEqual "Safe and secure!"
    }
  }
}
