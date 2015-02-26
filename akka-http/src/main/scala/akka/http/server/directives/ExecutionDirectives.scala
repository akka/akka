/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.http.util.FastFuture
import FastFuture._

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[akka.http.server.ExceptionHandler]].
   */
  def handleExceptions(handler: ExceptionHandler): Directive0 =
    Directive { innerRouteBuilder ⇒
      ctx ⇒
        import ctx.executionContext
        def handleException: PartialFunction[Throwable, Future[RouteResult]] =
          handler andThen (_(ctx.withAcceptAll))
        try innerRouteBuilder(())(ctx).fast.recoverWith(handleException)
        catch {
          case NonFatal(e) ⇒ handleException.applyOrElse[Throwable, Future[RouteResult]](e, throw _)
        }
    }

  /**
   * Transforms rejections produced by its inner route using the given
   * [[akka.http.server.RejectionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    extractRequestContext flatMap { ctx ⇒
      recoverRejectionsWith { rejections ⇒
        val filteredRejections = RejectionHandler.applyTransformations(rejections)
        handler(filteredRejections) match {
          case Some(route) ⇒
            val errorMsg = "The RejectionHandler for %s must not itself produce rejections (received %s)!"
            recoverRejections(r ⇒ sys.error(errorMsg.format(filteredRejections, r)))(route)(ctx.withAcceptAll)
          case None ⇒ FastFuture.successful(RouteResult.Rejected(filteredRejections))
        }
      }
    }
}

object ExecutionDirectives extends ExecutionDirectives