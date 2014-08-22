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

import akka.shapeless.HList

/**
 * A Route that can be implicitly converted into a Directive (fitting any signature).
 */
abstract class StandardRoute extends Route {
  def toDirective[L <: HList]: Directive[L] = StandardRoute.toDirective(this)
}

object StandardRoute {
  def apply(route: Route): StandardRoute = route match {
    case x: StandardRoute ⇒ x
    case x                ⇒ new StandardRoute { def apply(ctx: RequestContext): RouteResult = { x(ctx) } }
  }

  /**
   * Converts the route into a directive that never passes the request to its inner route
   * (and always returns its underlying route).
   */
  implicit def toDirective[L <: HList](route: Route): Directive[L] = Route.toDirective(route)
}