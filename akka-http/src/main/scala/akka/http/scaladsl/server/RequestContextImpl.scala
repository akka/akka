/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.concurrent.{ Future, ExecutionContextExecutor }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.event.LoggingAdapter
import akka.http.scaladsl.settings.{ RoutingSettings, ParserSettings }
import akka.http.scaladsl.marshalling.{ Marshal, ToResponseMarshallable }
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._

/**
 * INTERNAL API
 */
private[http] class RequestContextImpl(
  val request: HttpRequest,
  val unmatchedPath: Uri.Path,
  val executionContext: ExecutionContextExecutor,
  val materializer: Materializer,
  val log: LoggingAdapter,
  val settings: RoutingSettings,
  val parserSettings: ParserSettings) extends RequestContext {

  def this(request: HttpRequest, log: LoggingAdapter, settings: RoutingSettings, parserSettings: ParserSettings)(implicit ec: ExecutionContextExecutor, materializer: Materializer) =
    this(request, request.uri.path, ec, materializer, log, settings, parserSettings)

  def this(request: HttpRequest, log: LoggingAdapter, settings: RoutingSettings)(implicit ec: ExecutionContextExecutor, materializer: Materializer) =
    this(request, request.uri.path, ec, materializer, log, settings, ParserSettings(ActorMaterializer.downcast(materializer).system))

  def reconfigure(executionContext: ExecutionContextExecutor, materializer: Materializer, log: LoggingAdapter, settings: RoutingSettings): RequestContext =
    copy(executionContext = executionContext, materializer = materializer, log = log, routingSettings = settings)

  override def complete(trm: ToResponseMarshallable): Future[RouteResult] =
    trm(request)(executionContext)
      .fast.map(res ⇒ RouteResult.Complete(res))(executionContext)
      .fast.recoverWith {
        case Marshal.UnacceptableResponseContentTypeException(supported) ⇒
          attemptRecoveryFromUnacceptableResponseContentTypeException(trm, supported)
        case RejectionError(rej) ⇒
          Future.successful(RouteResult.Rejected(rej :: Nil))
      }(executionContext)

  override def reject(rejections: Rejection*): Future[RouteResult] =
    FastFuture.successful(RouteResult.Rejected(rejections.toList))

  override def fail(error: Throwable): Future[RouteResult] =
    FastFuture.failed(error)

  override def withRequest(request: HttpRequest): RequestContext =
    if (request != this.request) copy(request = request) else this

  override def withExecutionContext(executionContext: ExecutionContextExecutor): RequestContext =
    if (executionContext != this.executionContext) copy(executionContext = executionContext) else this

  override def withMaterializer(materializer: Materializer): RequestContext =
    if (materializer != this.materializer) copy(materializer = materializer) else this

  override def withLog(log: LoggingAdapter): RequestContext =
    if (log != this.log) copy(log = log) else this

  override def withRoutingSettings(routingSettings: RoutingSettings): RequestContext =
    if (routingSettings != this.settings) copy(routingSettings = routingSettings) else this

  override def withParserSettings(parserSettings: ParserSettings): RequestContext =
    if (parserSettings != this.parserSettings) copy(parserSettings = parserSettings) else this

  override def mapRequest(f: HttpRequest ⇒ HttpRequest): RequestContext =
    copy(request = f(request))

  override def withUnmatchedPath(path: Uri.Path): RequestContext =
    if (path != unmatchedPath) copy(unmatchedPath = path) else this

  override def mapUnmatchedPath(f: Uri.Path ⇒ Uri.Path): RequestContext =
    copy(unmatchedPath = f(unmatchedPath))

  override def withAcceptAll: RequestContext = request.header[headers.Accept] match {
    case Some(accept @ headers.Accept(ranges)) if !accept.acceptsAll ⇒
      mapRequest(_.mapHeaders(_.map {
        case `accept` ⇒
          val acceptAll =
            if (ranges.exists(_.isWildcard)) ranges.map(r ⇒ if (r.isWildcard) MediaRanges.`*/*;q=MIN` else r)
            else ranges :+ MediaRanges.`*/*;q=MIN`
          accept.copy(mediaRanges = acceptAll)
        case x ⇒ x
      }))
    case _ ⇒ this
  }

  /** Attempts recovering from the special case when non-2xx response is sent, yet content negotiation was unable to find a match. */
  private def attemptRecoveryFromUnacceptableResponseContentTypeException(trm: ToResponseMarshallable, supported: Set[ContentNegotiator.Alternative]): Future[RouteResult] =
    trm.value match {
      case (status: StatusCode, value) if !status.isSuccess ⇒ this.withAcceptAll.complete(trm) // retry giving up content negotiation
      case _ ⇒ Future.successful(RouteResult.Rejected(UnacceptedResponseContentTypeRejection(supported) :: Nil))
    }

  private def copy(request: HttpRequest = request,
                   unmatchedPath: Uri.Path = unmatchedPath,
                   executionContext: ExecutionContextExecutor = executionContext,
                   materializer: Materializer = materializer,
                   log: LoggingAdapter = log,
                   routingSettings: RoutingSettings = settings,
                   parserSettings: ParserSettings = parserSettings) =
    new RequestContextImpl(request, unmatchedPath, executionContext, materializer, log, routingSettings, parserSettings)
}
