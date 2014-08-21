/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import scala.collection.GenTraversableOnce
import scala.concurrent.Future

import akka.http.model.HttpResponse

/**
 * The result of handling a request.
 */
sealed trait RouteResult
case class CompleteWith(response: HttpResponse) extends RouteResult
case class RouteException(exception: Throwable) extends RouteResult
case class Rejected(rejections: List[Rejection]) extends RouteResult {
  def map(f: Rejection ⇒ Rejection) = Rejected(rejections.map(f))
  def flatMap(f: Rejection ⇒ GenTraversableOnce[Rejection]) = Rejected(rejections.flatMap(f))
}
case class DeferredResult(result: Future[RouteResult]) extends RouteResult
