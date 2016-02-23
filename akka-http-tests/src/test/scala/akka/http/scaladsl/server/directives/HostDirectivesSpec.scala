/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.headers.Host
import org.scalatest.FreeSpec

class HostDirectivesSpec extends FreeSpec with GenericRoutingSpec {
  "The 'host' directive" - {
    "in its simple String form should" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("spray.com") { completeOk }
        } ~> check { handled shouldEqual false }
      }

      "let requests to matching hosts pass" in {
        Get() ~> Host("spray.io") ~> {
          host("spray.com", "spray.io") { completeOk }
        } ~> check { response shouldEqual Ok }
      }
    }

    "in its simple RegEx form" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("hairspray.*".r) { echoComplete }
        } ~> check { handled shouldEqual false }
      }

      "let requests to matching hosts pass and extract the full host" in {
        Get() ~> Host("spray.io") ~> {
          host("spra.*".r) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "spray.io" }
      }
    }

    "in its group RegEx form" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("hairspray(.*)".r) { echoComplete }
        } ~> check { handled shouldEqual false }
      }

      "let requests to matching hosts pass and extract the full host" in {
        Get() ~> Host("spray.io") ~> {
          host("spra(.*)".r) { echoComplete }
        } ~> check { responseAs[String] shouldEqual "y.io" }
      }
    }
  }
}
