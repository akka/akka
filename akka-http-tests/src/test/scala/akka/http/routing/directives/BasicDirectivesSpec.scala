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

package akka.http.routing
package directives

import akka.http.model.HttpResponse
import akka.http.unmarshalling._

class BasicDirectivesSpec extends RoutingSpec {

  "The 'routeRouteResponse' directive" should {
    "in its simple String form" in {
      val addYeah = routeRouteResponse {
        case CompleteWith(HttpResponse(_, _, entity, _)) ⇒ complete(entity.asString.map(_ + "Yeah"))
      }
      Get() ~> addYeah(complete("abc")) ~> check { responseAs[String] mustEqual "abcYeah" }
    }
  }
}