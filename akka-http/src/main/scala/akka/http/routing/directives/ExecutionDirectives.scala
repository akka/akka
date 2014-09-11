/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package directives

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[akka.http.routing.ExceptionHandler]].
   */
  def handleExceptions(handler: ExceptionHandler): Directive0 =
    mapInnerRoute { inner ⇒
      ctx ⇒
        def handleError = handler andThen (_(ctx.withContentNegotiationDisabled))
        try inner {
          ctx withRouteResponseHandling {
            case RouteResult.Failure(error) if handler isDefinedAt error ⇒ handleError(error)
          }
        }
        catch handleError
    }

  /**
   * Transforms rejections produced by its inner route using the given
   * [[akka.http.routing.RejectionHandler]].
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
}

object ExecutionDirectives extends ExecutionDirectives