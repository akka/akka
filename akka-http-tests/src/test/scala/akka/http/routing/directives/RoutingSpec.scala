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

import _root_.akka.http.routing.{ Route, Directives }
import org.scalatest._
import _root_.akka.http.testkit.ScalatestRouteTest
import _root_.akka.http.model.HttpResponse

trait GenericRoutingSpec extends MustMatchers with Directives with ScalatestRouteTest { this: Suite ⇒
  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  def echoComplete[T]: T ⇒ Route = { x ⇒ complete(x.toString) }
  def echoComplete2[T, U]: (T, U) ⇒ Route = { (x, y) ⇒ complete(s"$x $y") }

  def sequential = {} // FIXME
}

abstract class RoutingSpec extends WordSpec with GenericRoutingSpec
