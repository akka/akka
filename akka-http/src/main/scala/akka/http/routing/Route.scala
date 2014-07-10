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

object Route {
  def apply(f: Route): Route = f

  /**
   * Converts the route into a directive that never passes the request to its inner route
   * (and always returns its underlying route).
   */
  def toDirective[L <: HList](route: Route): Directive[L] = new Directive[L] {
    def happly(f: L ⇒ Route) = route
  }
}