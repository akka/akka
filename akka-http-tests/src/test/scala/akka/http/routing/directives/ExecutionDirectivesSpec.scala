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

import akka.http.routing._
import akka.http.model.StatusCodes.InternalServerError

class ExecutionDirectivesSpec extends RoutingSpec {

  "the 'dynamicIf' directive" should {
    "cause its inner route to be revaluated for every request anew, if enabled" in {
      var a = ""
      val staticRoute = get { dynamicIf(enabled = false) { a += "x"; complete(a) } }
      val dynamicRoute = get { dynamic { a += "x"; complete(a) } }
      def expect(route: Route, s: String) = Get() ~> route ~> check { responseAs[String] mustEqual s }
      expect(staticRoute, "x")
      expect(staticRoute, "x")
      expect(staticRoute, "x")
      expect(dynamicRoute, "xx")
      expect(dynamicRoute, "xxx")
      expect(dynamicRoute, "xxxx")
    }
  }

  "the 'detach directive" should {
    "handle exceptions thrown inside its inner future" in {

      implicit val exceptionHandler = ExceptionHandler {
        case e: ArithmeticException ⇒ ctx ⇒
          ctx.complete(InternalServerError, "Oops.")
      }

      val route = get {
        detach() {
          complete((3 / 0).toString)
        }
      }

      Get() ~> route ~> check {
        status mustEqual InternalServerError
        responseAs[String] mustEqual "Oops."
      }
    }
  }
}
