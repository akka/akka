/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.collection.immutable

import akka.http.model.HttpResponse

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
}
