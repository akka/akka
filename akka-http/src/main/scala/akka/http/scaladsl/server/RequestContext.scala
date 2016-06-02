/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.concurrent.{ Future, ExecutionContextExecutor }
import akka.stream.Materializer
import akka.event.LoggingAdapter
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.{ RoutingSettings, ParserSettings }

/**
 * Immutable object encapsulating the context of an [[akka.http.scaladsl.model.HttpRequest]]
 * as it flows through a akka-http Route structure.
 */
trait RequestContext {

  /** The request this context represents. Modelled as a `val` so as to enable an `import ctx.request._`. */
  val request: HttpRequest

  /** The unmatched path of this context. Modelled as a `val` so as to enable an `import ctx.unmatchedPath._`. */
  val unmatchedPath: Uri.Path

  /**
   * The default ExecutionContext to be used for scheduling asynchronous logic related to this request.
   */
  implicit def executionContext: ExecutionContextExecutor

  /**
   * The default Materializer.
   */
  implicit def materializer: Materializer

  /**
   * The default LoggingAdapter to be used for logging messages related to this request.
   */
  def log: LoggingAdapter

  /**
   * The default RoutingSettings to be used for configuring directives.
   */
  def settings: RoutingSettings

  /**
   * The default ParserSettings to be used for configuring directives.
   */
  def parserSettings: ParserSettings

  /**
   * Returns a copy of this context with the given fields updated.
   */
  def reconfigure(
    executionContext: ExecutionContextExecutor = executionContext,
    materializer:     Materializer             = materializer,
    log:              LoggingAdapter           = log,
    settings:         RoutingSettings          = settings): RequestContext

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
   * directive and its `ExceptionHandler`, unless the error is a `RejectionError`. In this case the
   * wrapped rejection is unpacked and "executed".
   */
  def fail(error: Throwable): Future[RouteResult]

  /**
   * Returns a copy of this context with the new HttpRequest.
   */
  def withRequest(req: HttpRequest): RequestContext

  /**
   * Returns a copy of this context with the new HttpRequest.
   */
  def withExecutionContext(ec: ExecutionContextExecutor): RequestContext

  /**
   * Returns a copy of this context with the new HttpRequest.
   */
  def withMaterializer(materializer: Materializer): RequestContext

  /**
   * Returns a copy of this context with the new LoggingAdapter.
   */
  def withLog(log: LoggingAdapter): RequestContext

  /**
   * Returns a copy of this context with the new RoutingSettings.
   */
  def withRoutingSettings(settings: RoutingSettings): RequestContext

  /**
   * Returns a copy of this context with the new [[akka.http.scaladsl.settings.ParserSettings]].
   */
  def withParserSettings(settings: ParserSettings): RequestContext

  /**
   * Returns a copy of this context with the HttpRequest transformed by the given function.
   */
  def mapRequest(f: HttpRequest ⇒ HttpRequest): RequestContext

  /**
   * Returns a copy of this context with the unmatched path updated to the given one.
   */
  def withUnmatchedPath(path: Uri.Path): RequestContext

  /**
   * Returns a copy of this context with the unmatchedPath transformed by the given function.
   */
  def mapUnmatchedPath(f: Uri.Path ⇒ Uri.Path): RequestContext

  /**
   * Removes a potentially existing Accept header from the request headers.
   */
  def withAcceptAll: RequestContext
}
