/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.model.headers.Host
import org.scalatest.FreeSpec

class HostDirectivesSpec extends FreeSpec with GenericRoutingSpec {
  "The 'host' directive" - {
    "in its simple String form should" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("spray.com") { completeOk }
        } ~> check { handled mustEqual false }
      }

      "let requests to matching hosts pass" in {
        Get() ~> Host("spray.io") ~> {
          host("spray.com", "spray.io") { completeOk }
        } ~> check { response mustEqual Ok }
      }
    }

    "in its simple RegEx form" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("hairspray.*".r) { echoComplete }
        } ~> check { handled mustEqual false }
      }

      "let requests to matching hosts pass and extract the full host" in {
        Get() ~> Host("spray.io") ~> {
          host("spra.*".r) { echoComplete }
        } ~> check { responseAs[String] mustEqual "spray.io" }
      }
    }

    "in its group RegEx form" - {
      "block requests to unmatched hosts" in {
        Get() ~> Host("spray.io") ~> {
          host("hairspray(.*)".r) { echoComplete }
        } ~> check { handled mustEqual false }
      }

      "let requests to matching hosts pass and extract the full host" in {
        Get() ~> Host("spray.io") ~> {
          host("spra(.*)".r) { echoComplete }
        } ~> check { responseAs[String] mustEqual "y.io" }
      }
    }
  }
}