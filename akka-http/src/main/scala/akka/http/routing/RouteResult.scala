/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import akka.http.model.HttpResponse

/**
 * The result of handling a request.
 *
 * As a user you cannot create RouteResult instances directly.
 * Instead, use the RequestContext to achieve the desired effect.
 */
sealed trait RouteResult

object RouteResult {
  final case class Complete private[RouteResult] (response: HttpResponse) extends RouteResult
  final case class Failure private[RouteResult] (exception: Throwable) extends RouteResult
  final case class Rejected private[RouteResult] (rejections: List[Rejection]) extends RouteResult
  private[http] def complete(response: HttpResponse) = Complete(response)
  private[http] def failure(exception: Throwable) = Failure(exception)
  private[http] def rejected(rejections: List[Rejection]) = Rejected(rejections)
}
