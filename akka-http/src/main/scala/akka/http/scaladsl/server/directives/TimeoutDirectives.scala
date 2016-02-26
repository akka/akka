/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.`Timeout-Access`
import akka.http.scaladsl.server.{ Directive, Directive0 }

import scala.concurrent.duration.Duration

trait TimeoutDirectives {

  def withoutRequestTimeout: Directive0 =
    withRequestTimeout(Duration.Inf)

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeout(timeout: Duration): Directive0 =
    withRequestTimeout(timeout, None)

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   *
   * @param handler optional custom "timeout response" function. If left None, the default timeout HttpResponse will be used.
   */
  def withRequestTimeout(timeout: Duration, handler: HttpRequest ⇒ HttpResponse): Directive0 =
    withRequestTimeout(timeout, Some(handler))

  /**
   * Tries to set a new request timeout and handler (if provided) at the same time.
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   *
   * @param handler optional custom "timeout response" function. If left None, the default timeout HttpResponse will be used.
   */
  def withRequestTimeout(timeout: Duration, handler: Option[HttpRequest ⇒ HttpResponse]): Directive0 =
    Directive { inner ⇒
      ctx ⇒
        ctx.request.header[`Timeout-Access`] match {
          case Some(t) ⇒
            handler match {
              case Some(h) ⇒ t.timeoutAccess.update(timeout, h)
              case _       ⇒ t.timeoutAccess.updateTimeout(timeout)
            }
          case _ ⇒ ctx.log.warning("withRequestTimeout was used in route however no request-timeout is set!")
        }
        inner()(ctx)
    }

  /**
   * Tries to set a new request timeout handler, which produces the timeout response for a
   * given request. Note that the handler must produce the response synchronously and shouldn't block!
   *
   * Due to the inherent raciness it is not guaranteed that the update will be applied before
   * the previously set timeout has expired!
   */
  def withRequestTimeoutResponse(handler: HttpRequest ⇒ HttpResponse): Directive0 =
    Directive { inner ⇒
      ctx ⇒
        ctx.request.header[`Timeout-Access`] match {
          case Some(t) ⇒ t.timeoutAccess.updateHandler(handler)
          case _       ⇒ ctx.log.warning("withRequestTimeoutResponse was used in route however no request-timeout is set!")
        }
        inner()(ctx)
    }

}

object TimeoutDirectives extends TimeoutDirectives