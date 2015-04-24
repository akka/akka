/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server

import scala.collection.immutable
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }

/**
 * The result of handling a request.
 *
 * As a user you cannot create RouteResult instances directly.
 * Instead, use the RequestContext to achieve the desired effect.
 */
sealed trait RouteResult

object RouteResult {
  final case class Complete(response: HttpResponse) extends RouteResult
  final case class Rejected(rejections: immutable.Seq[Rejection]) extends RouteResult

  implicit def route2HandlerFlow(route: Route)(implicit setup: RoutingSetup): Flow[HttpRequest, HttpResponse, Unit] =
    Route.handlerFlow(route)
}
