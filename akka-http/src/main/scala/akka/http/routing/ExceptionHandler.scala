/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import akka.http.model._
import StatusCodes._

trait ExceptionHandler extends ExceptionHandler.PF {
  def isDefault: Boolean
}

object ExceptionHandler {
  type PF = PartialFunction[Throwable, Route]

  implicit def apply(pf: PF): ExceptionHandler = apply(default = false)(pf)

  private def apply(default: Boolean)(pf: PF): ExceptionHandler =
    new ExceptionHandler {
      def isDefault: Boolean = default
      def isDefinedAt(error: Throwable) = pf.isDefinedAt(error)
      def apply(error: Throwable) = pf(error)
    }

  def default(settings: RoutingSettings)(implicit ec: ExecutionContext): ExceptionHandler =
    apply(default = true) {
      case e: IllegalRequestException ⇒ ctx ⇒ {
        ctx.log.warning("Illegal request {}\n\t{}\n\tCompleting with '{}' response",
          ctx.request, e.getMessage, e.status)
        ctx.complete(e.status, e.info.format(settings.verboseErrorMessages))
      }
      case e: RequestProcessingException ⇒ ctx ⇒ {
        ctx.log.warning("Request {} could not be handled normally\n\t{}\n\tCompleting with '{}' response",
          ctx.request, e.getMessage, e.status)
        ctx.complete(e.status, e.info.format(settings.verboseErrorMessages))
      }
      case NonFatal(e) ⇒ ctx ⇒ {
        ctx.log.error(e, "Error during processing of request {}", ctx.request)
        ctx.complete(InternalServerError)
      }
    }
}
