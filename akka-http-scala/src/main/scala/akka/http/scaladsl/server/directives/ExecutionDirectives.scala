/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._

trait ExecutionDirectives {
  import BasicDirectives._

  /**
   * Transforms exceptions thrown during evaluation of its inner route using the given
   * [[akka.http.scaladsl.server.ExceptionHandler]].
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
   * [[akka.http.scaladsl.server.RejectionHandler]].
   */
  def handleRejections(handler: RejectionHandler): Directive0 =
    extractRequestContext flatMap { ctx ⇒
      // allow for up to 8 nested rejections from RejectionHandler before bailing out
      def handle(rejections: immutable.Seq[Rejection], iterationsLeft: Int = 8): Future[RouteResult] =
        if (iterationsLeft > 0) {
          handler(rejections) match {
            case Some(route) ⇒ recoverRejectionsWith(handle(_, iterationsLeft - 1))(route)(ctx.withAcceptAll)
            case None        ⇒ FastFuture.successful(RouteResult.Rejected(rejections))
          }
        } else sys.error(s"Detected infinite (?) handler cycle for rejections $rejections")
      recoverRejectionsWith(rejections ⇒ handle(RejectionHandler.applyTransformations(rejections)))
    }
}

object ExecutionDirectives extends ExecutionDirectives