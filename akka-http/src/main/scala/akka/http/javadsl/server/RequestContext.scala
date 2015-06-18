/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.model._
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }

/**
 * The RequestContext represents the state of the request while it is routed through
 * the route structure.
 */
trait RequestContext {
  /**
   * The incoming request.
   */
  def request: HttpRequest

  /**
   * The still unmatched path of the request.
   */
  def unmatchedPath: String

  /**
   * Completes the request with a value of type T and marshals it using the given
   * marshaller.
   */
  def completeAs[T](marshaller: Marshaller[T], value: T): RouteResult

  /**
   * Completes the request with the given response.
   */
  def complete(response: HttpResponse): RouteResult

  /**
   * Completes the request with the given string as an entity of type `text/plain`.
   */
  def complete(text: String): RouteResult

  /**
   * Completes the request with the given status code and no entity.
   */
  def completeWithStatus(statusCode: StatusCode): RouteResult

  /**
   * Completes the request with the given status code and no entity.
   */
  def completeWithStatus(statusCode: Int): RouteResult

  /**
   * Defers completion of the request
   */
  def completeWith(futureResult: Future[RouteResult]): RouteResult

  /**
   * Explicitly rejects the request as not found. Other route alternatives
   * may still be able provide a response.
   */
  def notFound(): RouteResult

  /** Returns the ExecutionContext of this RequestContext */
  def executionContext(): ExecutionContext

  // FIXME: provide proper support for rejections, see #16438
}