/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.event.LoggingAdapter
import akka.stream.FlowMaterializer
import akka.http.marshalling.ToResponseMarshallable
import akka.http.util.{ Deferrable, identityFunc }
import akka.http.model._

/**
 * INTERNAL API
 */
private[http] class RequestContextImpl(
  val request: HttpRequest,
  val unmatchedPath: Uri.Path,
  val executionContext: ExecutionContext,
  val flowMaterializer: FlowMaterializer,
  val log: LoggingAdapter,
  finish: RouteResult ⇒ Deferrable[RouteResult] = Deferrable(_)) extends RequestContext {

  def this(request: HttpRequest, log: LoggingAdapter)(implicit ec: ExecutionContext, fm: FlowMaterializer) =
    this(request, request.uri.path, ec, fm, log)

  def reconfigure(executionContext: ExecutionContext,
                  flowMaterializer: FlowMaterializer,
                  log: LoggingAdapter): RequestContext =
    copy(executionContext = executionContext, flowMaterializer = flowMaterializer, log = log)

  override def complete(trm: ToResponseMarshallable): Deferrable[RouteResult] =
    trm(request)(executionContext)
      .map(res ⇒ RouteResult.complete(res))(executionContext)
      .recover {
        case RejectionError(rej) ⇒ RouteResult.rejected(rej :: Nil)
        case error               ⇒ RouteResult.failure(error)
      }(executionContext)
      .flatMap(finish)(executionContext)

  override def reject(rejections: Rejection*): Deferrable[RouteResult] =
    finish(RouteResult.rejected(rejections.toList))

  override def fail(error: Throwable): Deferrable[RouteResult] =
    finish(RouteResult.failure(error))

  override def withRequest(req: HttpRequest): RequestContext =
    copy(request = req)

  override def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext =
    copy(request = f(request))

  override def withUnmatchedPath(path: Uri.Path): RequestContext =
    copy(unmatchedPath = path)

  override def withUnmatchedPathMapped(f: Uri.Path ⇒ Uri.Path): RequestContext =
    copy(unmatchedPath = f(unmatchedPath))

  override def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext =
    copy(finish = f andThen finish)

  override def withRouteResponseMappedPF(pf: PartialFunction[RouteResult, RouteResult]): RequestContext =
    withRouteResponseMapped(pf.applyOrElse(_, identityFunc[RouteResult]))

  override def withRouteResponseFlatMapped(f: RouteResult ⇒ Deferrable[RouteResult]): RequestContext =
    copy(finish = rr ⇒ f(rr).flatMap(finish)(executionContext))

  override def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext =
    withRouteResponseMappedPF {
      case RouteResult.Complete(response) ⇒ RouteResult.complete(f(response))
    }

  override def withHttpResponseEntityMapped(f: HttpEntity ⇒ HttpEntity): RequestContext =
    withHttpResponseMapped(_ mapEntity f)

  override def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext =
    withHttpResponseMapped(_ mapHeaders f)

  override def withRejectionsMapped(f: List[Rejection] ⇒ List[Rejection]): RequestContext =
    withRouteResponseMappedPF {
      case RouteResult.Rejected(rejs) ⇒ RouteResult.rejected(f(rejs))
    }

  override def withRejectionHandling(f: List[Rejection] ⇒ Deferrable[RouteResult]): RequestContext =
    withRouteResponseHandling {
      case RouteResult.Rejected(rejs) ⇒
        // `finish` is *not* chained in here, because the user already applied it when creating the result of f
        f(rejs)
    }

  override def withExceptionHandling(pf: PartialFunction[Throwable, Deferrable[RouteResult]]): RequestContext =
    withRouteResponseHandling {
      case RouteResult.Failure(error) if pf isDefinedAt error ⇒
        // `finish` is *not* chained in here, because the user already applied it when creating the result of pf
        pf(error)
    }

  def withRouteResponseHandling(pf: PartialFunction[RouteResult, Deferrable[RouteResult]]): RequestContext =
    copy(finish = pf.applyOrElse(_, finish))

  override def withContentNegotiationDisabled: RequestContext =
    copy(request = request.withHeaders(request.headers filterNot (_.isInstanceOf[headers.Accept])))

  private def copy(request: HttpRequest = request,
                   unmatchedPath: Uri.Path = unmatchedPath,
                   executionContext: ExecutionContext = executionContext,
                   flowMaterializer: FlowMaterializer = flowMaterializer,
                   log: LoggingAdapter = log,
                   finish: RouteResult ⇒ Deferrable[RouteResult] = finish) =
    new RequestContextImpl(request, unmatchedPath, executionContext, flowMaterializer, log, finish)
}
