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

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import akka.actor._

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[spray.routing.ExceptionHandler]].
   */
  def handleExceptions(handler: ExceptionHandler): Directive0 =
    mapInnerRoute { inner ⇒
      ctx ⇒
        def handleError = handler andThen (_(ctx.withContentNegotiationDisabled))
        try inner {
          ctx withRouteResponseHandling {
            case Status.Failure(error) if handler isDefinedAt error ⇒ handleError(error)
          }
        }
        catch handleError
    }

  /**
   * Transforms rejections produced by its inner route using the given
   * [[spray.routing.RejectionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    mapRequestContext { ctx ⇒
      ctx withRejectionHandling { rejections ⇒
        val filteredRejections = RejectionHandler.applyTransformations(rejections)
        if (handler isDefinedAt filteredRejections)
          handler(filteredRejections) {
            ctx.withContentNegotiationDisabled withRejectionHandling { r ⇒
              sys.error(s"The RejectionHandler for $rejections must not itself produce rejections (received $r)!")
            }
          }
        else ctx.reject(filteredRejections: _*)
      }
    }

  /**
   * A directive that evaluates its inner Route for every request anew. Note that this directive has no additional
   * effect when used inside (or some level underneath) a directive extracting one or more values, since everything
   * inside a directive extracting values is _always_ reevaluated for every request.
   *
   * Also Note that this directive differs from most other directives in that it cannot be combined with other routes
   * via the usual `&` and `|` operators.
   */
  /* directive */ def dynamic: ByNameDirective0 = ExecutionDirectives._dynamic

  /**
   * A directive that evaluates its inner Route for every request anew, if the given enabled flag is true.
   * Note that this directive has no additional effect when used inside (or some level underneath) a directive
   * extracting one or more values, since everything inside a directive extracting values is _always_ reevaluated for
   * every request.
   *
   * Also Note that this directive differs from most other directives in that it cannot be combined with other routes
   * via the usual `&` and `|` operators.
   */
  /* directive */ def dynamicIf(enabled: Boolean): ByNameDirective0 = ByNameDirective0(enabled)

  /**
   * Executes its inner Route in a `Future`.
   */
  def detach(dm: DetachMagnet): Directive0 = {
    import dm._
    mapInnerRoute { inner ⇒
      ctx ⇒
        Future(inner(ctx)).onFailure { case e ⇒ ctx.failWith(e) }
    }
  }
}

object ExecutionDirectives extends ExecutionDirectives {
  private val _dynamic = dynamicIf(enabled = true)
}
case class ByNameDirective0(enabled: Boolean) {
  def apply(inner: ⇒ Route): Route =
    if (enabled) Route(ctx ⇒ inner(ctx)) else inner
}

class DetachMagnet()(implicit val ec: ExecutionContext)

object DetachMagnet {
  implicit def fromUnit(u: Unit)(implicit dm2: DetachMagnet2) = new DetachMagnet()(dm2.ec)
  implicit def fromExecutionContext(ec: ExecutionContext) = new DetachMagnet()(ec)
}

class DetachMagnet2(val ec: ExecutionContext)

object DetachMagnet2 extends DetachMagnet2LowerPriorityImplicits {
  implicit def fromImplicitExecutionContext(implicit ec: ExecutionContext) = new DetachMagnet2(ec)
}

private[directives] abstract class DetachMagnet2LowerPriorityImplicits {
  implicit def fromImplicitRefFactory(implicit factory: ActorRefFactory) = new DetachMagnet2(factory.dispatcher)
}
