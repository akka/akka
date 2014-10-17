/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.util.FastFuture
import FastFuture._

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[akka.http.server.ExceptionHandler]].
   */
  def handleExceptions(handler: ExceptionHandler): Directive0 =
    withRequestContext flatMap { ctx ⇒
      import ctx.executionContext
      mapRouteResultFuture {
        _.fast.recoverWith(handler andThen (_(ctx.withContentNegotiationDisabled)))
      }
    }

  /**
   * Transforms rejections produced by its inner route using the given
   * [[akka.http.server.RejectionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    withRequestContext flatMap { ctx ⇒
      recoverRejectionsWith { rejections ⇒
        val filteredRejections = RejectionHandler.applyTransformations(rejections)
        if (handler isDefinedAt filteredRejections) {
          val errorMsg = "The RejectionHandler for %s must not itself produce rejections (received %s)!"
          recoverRejections(r ⇒ sys.error(errorMsg.format(filteredRejections, r))) {
            handler(filteredRejections)
          }(ctx.withContentNegotiationDisabled)
        } else FastFuture.successful(RouteResult.Rejected(filteredRejections))
      }
    }
}

object ExecutionDirectives extends ExecutionDirectives