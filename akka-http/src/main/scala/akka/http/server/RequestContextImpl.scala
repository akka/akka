/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.stream.FlowMaterializer

import scala.concurrent.{ Future, ExecutionContext }
import akka.event.LoggingAdapter
import akka.http.marshalling.{ Marshal, ToResponseMarshallable }
import akka.http.util.FastFuture
import akka.http.model._
import FastFuture._

/**
 * INTERNAL API
 */
private[http] class RequestContextImpl(
  val request: HttpRequest,
  val unmatchedPath: Uri.Path,
  val executionContext: ExecutionContext,
  val flowMaterializer: FlowMaterializer,
  val log: LoggingAdapter,
  val settings: RoutingSettings) extends RequestContext {

  def this(request: HttpRequest, log: LoggingAdapter, settings: RoutingSettings)(implicit ec: ExecutionContext, materializer: FlowMaterializer) =
    this(request, request.uri.path, ec, materializer, log, settings)

  def reconfigure(executionContext: ExecutionContext, flowMaterializer: FlowMaterializer, log: LoggingAdapter, settings: RoutingSettings): RequestContext =
    copy(executionContext = executionContext, flowMaterializer = flowMaterializer, log = log, settings = settings)

  override def complete(trm: ToResponseMarshallable): Future[RouteResult] =
    trm(request)(executionContext)
      .fast.map(res ⇒ RouteResult.Complete(res))(executionContext)
      .fast.recover {
        case Marshal.UnacceptableResponseContentTypeException(supported) ⇒
          RouteResult.Rejected(UnacceptedResponseContentTypeRejection(supported) :: Nil)
        case RejectionError(rej) ⇒ RouteResult.Rejected(rej :: Nil)
      }(executionContext)

  override def reject(rejections: Rejection*): Future[RouteResult] =
    FastFuture.successful(RouteResult.Rejected(rejections.toVector))

  override def fail(error: Throwable): Future[RouteResult] =
    FastFuture.failed(error)

  override def withRequest(request: HttpRequest): RequestContext =
    if (request != this.request) copy(request = request) else this

  override def withExecutionContext(executionContext: ExecutionContext): RequestContext =
    if (executionContext != this.executionContext) copy(executionContext = executionContext) else this

  override def withFlowMaterializer(flowMaterializer: FlowMaterializer): RequestContext =
    if (flowMaterializer != this.flowMaterializer) copy(flowMaterializer = flowMaterializer) else this

  override def withLog(log: LoggingAdapter): RequestContext =
    if (log != this.log) copy(log = log) else this

  override def withSettings(settings: RoutingSettings): RequestContext =
    if (settings != this.settings) copy(settings = settings) else this

  override def mapRequest(f: HttpRequest ⇒ HttpRequest): RequestContext =
    copy(request = f(request))

  override def withUnmatchedPath(path: Uri.Path): RequestContext =
    if (path != unmatchedPath) copy(unmatchedPath = path) else this

  override def mapUnmatchedPath(f: Uri.Path ⇒ Uri.Path): RequestContext =
    copy(unmatchedPath = f(unmatchedPath))

  override def withContentNegotiationDisabled: RequestContext =
    copy(request = request.withHeaders(request.headers filterNot (_.isInstanceOf[headers.Accept])))

  private def copy(request: HttpRequest = request,
                   unmatchedPath: Uri.Path = unmatchedPath,
                   executionContext: ExecutionContext = executionContext,
                   flowMaterializer: FlowMaterializer = flowMaterializer,
                   log: LoggingAdapter = log,
                   settings: RoutingSettings = settings) =
    new RequestContextImpl(request, unmatchedPath, executionContext, flowMaterializer, log, settings)
}
