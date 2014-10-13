/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[akka.http.server.ExceptionHandler]].
   */
  def handleExceptions(handler: ExceptionHandler): Directive0 =
    Directive.mapResult { (ctx, result) ⇒
      def handleError = handler andThen (_(ctx.withContentNegotiationDisabled))
      result.recoverWith {
        case error if handler isDefinedAt error ⇒ handleError(error)
      }(ctx.executionContext)
    }

  /**
   * Transforms rejections produced by its inner route using the given
   * [[akka.http.server.RejectionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    Directive.mapResult { (ctx, result) ⇒
      result.recoverRejectionsWith {
        case rejections ⇒
          val filteredRejections = RejectionHandler.applyTransformations(rejections)
          if (handler isDefinedAt filteredRejections)
            handler(filteredRejections)(ctx.withContentNegotiationDisabled).recoverRejections { r ⇒
              sys.error(s"The RejectionHandler for $rejections must not itself produce rejections (received $r)!")
            }(ctx.executionContext)
          else ctx.reject(filteredRejections: _*)
      }(ctx.executionContext)
    }
}

object ExecutionDirectives extends ExecutionDirectives