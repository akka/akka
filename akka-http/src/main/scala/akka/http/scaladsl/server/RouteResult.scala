/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }

/**
 * The result of handling a request.
 *
 * As a user you typically don't create RouteResult instances directly.
 * Instead, use the methods on the [[RequestContext]] to achieve the desired effect.
 */
sealed trait RouteResult

object RouteResult {
  final case class Complete(response: HttpResponse) extends RouteResult
  final case class Rejected(rejections: immutable.Seq[Rejection]) extends RouteResult

  implicit def route2HandlerFlow(route: Route)(implicit routingSettings: RoutingSettings,
                                               materializer: Materializer,
                                               routingLog: RoutingLog,
                                               executionContext: ExecutionContext = null,
                                               rejectionHandler: RejectionHandler = RejectionHandler.default,
                                               exceptionHandler: ExceptionHandler = null): Flow[HttpRequest, HttpResponse, Unit] =
    Route.handlerFlow(route)
}
