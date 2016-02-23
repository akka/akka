/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.language.implicitConversions
import scala.concurrent.Future
import akka.http.javadsl.{ server ⇒ js }
import akka.http.scaladsl.{ server ⇒ ss }

/**
 * INTERNAL API
 */
private[http] class RouteResultImpl(val underlying: Future[ss.RouteResult]) extends js.RouteResult

/**
 * INTERNAL API
 */
private[http] object RouteResultImpl {
  implicit def autoConvert(result: Future[ss.RouteResult]): js.RouteResult =
    new RouteResultImpl(result)
}

/**
 * Internal result that flags that a rejection was not handled by a rejection handler.
 *
 * INTERNAL API
 */
private[http] case object PassRejectionRouteResult extends js.RouteResult