/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.collection.immutable

import scala.concurrent.{ ExecutionContext, Future }

import akka.http.util.FastFuture
import FastFuture._

import akka.http.model.HttpResponse

package object server {

  type Route = RequestContext ⇒ Future[RouteResult]
  type RouteGenerator[T] = T ⇒ Route
  type Directive0 = Directive[Unit]
  type Directive1[T] = Directive[Tuple1[T]]
  type PathMatcher0 = PathMatcher[Unit]
  type PathMatcher1[T] = PathMatcher[Tuple1[T]]

  /**
   * Helper for constructing a Route from a function literal.
   */
  def Route(f: Route): Route = f

  def FIXME = throw new RuntimeException("Not yet implemented")

  private[http] implicit class EnhanceFutureRouteResult(val result: Future[RouteResult]) extends AnyVal {
    def mapResponse(f: HttpResponse ⇒ RouteResult)(implicit ec: ExecutionContext): Future[RouteResult] =
      mapResponseWith(response ⇒ FastFuture.successful(f(response)))

    def mapResponseWith(f: HttpResponse ⇒ Future[RouteResult])(implicit ec: ExecutionContext): Future[RouteResult] =
      result.fast.flatMap {
        case RouteResult.Complete(response) ⇒ f(response)
        case r: RouteResult.Rejected        ⇒ FastFuture.successful(r)
      }

    def recoverRejections(f: immutable.Seq[Rejection] ⇒ RouteResult)(implicit ec: ExecutionContext): Future[RouteResult] =
      recoverRejectionsWith(rej ⇒ FastFuture.successful(f(rej)))

    def recoverRejectionsWith(f: immutable.Seq[Rejection] ⇒ Future[RouteResult])(implicit ec: ExecutionContext): Future[RouteResult] =
      result.fast.flatMap {
        case c: RouteResult.Complete          ⇒ FastFuture.successful(c)
        case RouteResult.Rejected(rejections) ⇒ f(rejections)
      }
  }
}