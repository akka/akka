/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.collection.immutable
import scala.concurrent.{ Future, ExecutionContext }
import akka.event.LoggingAdapter
import akka.http.marshalling.ToResponseMarshallable
import akka.http.model._

/**
 * Immutable object encapsulating the context of an [[akka.http.model.HttpRequest]]
 * as it flows through a akka-http Route structure.
 */
trait RequestContext {

  /** The request this context represents. Modelled as a ``val`` so as to enable an ``import ctx.request._``. */
  val request: HttpRequest

  /** The unmatched path of this context. Modelled as a ``val`` so as to enable an ``import ctx.unmatchedPath._``. */
  val unmatchedPath: Uri.Path

  /**
   * The default ExecutionContext to be used for scheduling asynchronous logic related to this request.
   */
  def executionContext: ExecutionContext

  /**
   * The default LoggingAdapter to be used for logging messages related to this request.
   */
  def log: LoggingAdapter

  /**
   * Returns a copy of this context with the given fields updated.
   */
  def reconfigure(executionContext: ExecutionContext = executionContext, log: LoggingAdapter = log): RequestContext

  /**
   * Completes the request with the given ToResponseMarshallable.
   */
  def complete(obj: ToResponseMarshallable): Future[RouteResult]

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejections: Rejection*): Future[RouteResult]

  /**
   * Bubbles the given error up the response chain where it is dealt with by the closest `handleExceptions`
   * directive and its ``ExceptionHandler``, unless the error is a ``RejectionError``. In this case the
   * wrapped rejection is unpacked and "executed".
   */
  def fail(error: Throwable): Future[RouteResult]

  /**
   * Returns a copy of this context with the new HttpRequest.
   */
  def withRequest(req: HttpRequest): RequestContext

  /**
   * Returns a copy of this context with the HttpRequest transformed by the given function.
   */
  def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext

  /**
   * Returns a copy of this context with the unmatched path updated to the given one.
   */
  def withUnmatchedPath(path: Uri.Path): RequestContext

  /**
   * Returns a copy of this context with the unmatchedPath transformed by the given function.
   */
  def withUnmatchedPathMapped(f: Uri.Path ⇒ Uri.Path): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withRouteResponseMappedPF(f: PartialFunction[RouteResult, RouteResult]): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withRouteResponseFlatMapped(f: RouteResult ⇒ Future[RouteResult]): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseEntityMapped(f: ResponseEntity ⇒ ResponseEntity): RequestContext

  /**
   * Returns a copy of this context with the given response transformation function chained into the response chain.
   */
  def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext

  /**
   * Returns a copy of this context with the given rejection transformation function chained into the response chain.
   */
  def withRejectionsMapped(f: immutable.Seq[Rejection] ⇒ immutable.Seq[Rejection]): RequestContext

  /**
   * Returns a copy of this context with the given rejection handling function chained into the response chain.
   */
  def withRejectionHandling(f: immutable.Seq[Rejection] ⇒ Future[RouteResult]): RequestContext

  /**
   * Returns a copy of this context with the given exception handling function chained into the response chain.
   */
  def withExceptionHandling(pf: PartialFunction[Throwable, Future[RouteResult]]): RequestContext

  /**
   * Returns a copy of this context with the given function handling a part of the response space.
   */
  def withRouteResponseHandling(pf: PartialFunction[RouteResult, Future[RouteResult]]): RequestContext

  /**
   * Removes a potentially existing Accept header from the request headers.
   */
  def withContentNegotiationDisabled: RequestContext
}
