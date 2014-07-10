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

trait RouteConcatenation {

  implicit def pimpRouteWithConcatenation(route: Route) = new RouteConcatenation(route: Route)

  class RouteConcatenation(route: Route) {

    /**
     * Returns a Route that chains two Routes. If the first Route rejects the request the second route is given a
     * chance to act upon the request.
     */
    def ~(other: Route): Route = { ctx ⇒
      route {
        ctx.withRejectionHandling { rejections ⇒
          other(ctx.withRejectionsMapped(rejections ++ _))
        }
      }
    }
  }

}

object RouteConcatenation extends RouteConcatenation