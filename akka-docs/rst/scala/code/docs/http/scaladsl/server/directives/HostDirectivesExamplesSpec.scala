/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import docs.http.scaladsl.server.RoutingSpec
import headers._
import StatusCodes._

class HostDirectivesExamplesSpec extends RoutingSpec {

  "extractHost" in {
    val route =
      extractHost { hn =>
        complete(s"Hostname: $hn")
      }

    // tests:
    Get() ~> Host("company.com", 9090) ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Hostname: company.com"
    }
  }

  "list-of-hosts" in {
    val route =
      host("api.company.com", "rest.company.com") {
        complete("Ok")
      }

    // tests:
    Get() ~> Host("rest.company.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Ok"
    }

    Get() ~> Host("notallowed.company.com") ~> route ~> check {
      handled shouldBe false
    }
  }

  "predicate" in {
    val shortOnly: String => Boolean = (hostname) => hostname.length < 10

    val route =
      host(shortOnly) {
        complete("Ok")
      }

    // tests:
    Get() ~> Host("short.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Ok"
    }

    Get() ~> Host("verylonghostname.com") ~> route ~> check {
      handled shouldBe false
    }
  }

  "using-regex" in {
    val route =
      host("api|rest".r) { prefix =>
        complete(s"Extracted prefix: $prefix")
      } ~
        host("public.(my|your)company.com".r) { captured =>
          complete(s"You came through $captured company")
        }

    // tests:
    Get() ~> Host("api.company.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "Extracted prefix: api"
    }

    Get() ~> Host("public.mycompany.com") ~> route ~> check {
      status shouldEqual OK
      responseAs[String] shouldEqual "You came through my company"
    }
  }

  "failing-regex" in {
    an[IllegalArgumentException] should be thrownBy {
      host("server-([0-9]).company.(com|net|org)".r) { target =>
        complete("Will never complete :'(")
      }
    }
  }

}
