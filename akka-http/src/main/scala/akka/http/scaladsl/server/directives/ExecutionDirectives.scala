/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
      val maxIterations = 8
      // allow for up to `maxIterations` nested rejections from RejectionHandler before bailing out
      def handle(rejections: immutable.Seq[Rejection], originalRejections: immutable.Seq[Rejection], iterationsLeft: Int = maxIterations): Future[RouteResult] =
        if (iterationsLeft > 0) {
          handler(rejections) match {
            case Some(route) ⇒ recoverRejectionsWith(handle(_, originalRejections, iterationsLeft - 1))(route)(ctx.withAcceptAll)
            case None        ⇒ FastFuture.successful(RouteResult.Rejected(rejections))
          }
        } else
          sys.error(s"Rejection handler still produced new rejections after $maxIterations iterations. " +
            s"Is there an infinite handler cycle? Initial rejections: $originalRejections final rejections: $rejections")

      recoverRejectionsWith { rejections ⇒
        val transformed = RejectionHandler.applyTransformations(rejections)
        handle(transformed, transformed)
      }
    }
}

object ExecutionDirectives extends ExecutionDirectives