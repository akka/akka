/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.util.control.NonFatal
import akka.http.scaladsl.settings.RoutingSettings
import akka.http.scaladsl.model._
import StatusCodes._

trait ExceptionHandler extends ExceptionHandler.PF {

  /**
   * Creates a new [[ExceptionHandler]] which uses the given one as fallback for this one.
   */
  def withFallback(that: ExceptionHandler): ExceptionHandler

  /**
   * "Seals" this handler by attaching a default handler as fallback if necessary.
   */
  def seal(settings: RoutingSettings): ExceptionHandler
}

object ExceptionHandler {
  type PF = PartialFunction[Throwable, Route]

  implicit def apply(pf: PF): ExceptionHandler = apply(knownToBeSealed = false)(pf)

  private def apply(knownToBeSealed: Boolean)(pf: PF): ExceptionHandler =
    new ExceptionHandler {
      def isDefinedAt(error: Throwable) = pf.isDefinedAt(error)
      def apply(error: Throwable) = pf(error)
      def withFallback(that: ExceptionHandler): ExceptionHandler =
        if (!knownToBeSealed) ExceptionHandler(knownToBeSealed = false)(this orElse that) else this
      def seal(settings: RoutingSettings): ExceptionHandler =
        if (!knownToBeSealed) ExceptionHandler(knownToBeSealed = true)(this orElse default(settings)) else this
    }

  def default(settings: RoutingSettings): ExceptionHandler =
    apply(knownToBeSealed = true) {
      case IllegalRequestException(info, status) ⇒ ctx ⇒ {
        ctx.log.warning("Illegal request {}\n\t{}\n\tCompleting with '{}' response",
          ctx.request, info.formatPretty, status)
        ctx.complete((status, info.format(settings.verboseErrorMessages)))
      }
      case NonFatal(e) ⇒ ctx ⇒ {
        ctx.log.error(e, "Error during processing of request {}", ctx.request)
        ctx.complete(InternalServerError)
      }
    }

  /**
   * Creates a sealed ExceptionHandler from the given one. Returns the default handler if the given one
   * is `null`.
   */
  def seal(handler: ExceptionHandler)(implicit settings: RoutingSettings): ExceptionHandler =
    if (handler ne null) handler.seal(settings) else ExceptionHandler.default(settings)
}
