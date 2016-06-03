/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.javadsl.model.HttpRequest
import akka.http.scaladsl.util.FastFuture._
import scala.concurrent.ExecutionContextExecutor
import akka.stream.Materializer
import akka.event.LoggingAdapter
import akka.http.javadsl.settings.RoutingSettings
import akka.http.javadsl.settings.ParserSettings
import akka.http.javadsl.model.HttpResponse
import java.util.concurrent.CompletionStage
import java.util.function.{ Function ⇒ JFunction }
import akka.http.scaladsl
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import scala.compat.java8.FutureConverters._
import scala.annotation.varargs
import akka.http.scaladsl.model.Uri.Path

class RequestContext private (val delegate: scaladsl.server.RequestContext) {
  import RequestContext._
  import RoutingJavaMapping._

  def getRequest: HttpRequest = delegate.request
  def getUnmatchedPath: String = delegate.unmatchedPath.toString()
  def getExecutionContext: ExecutionContextExecutor = delegate.executionContext
  def getMaterializer: Materializer = delegate.materializer
  def getLog: LoggingAdapter = delegate.log
  def getSettings: RoutingSettings = delegate.settings
  def getParserSettings: ParserSettings = delegate.parserSettings

  def reconfigure(
    executionContext: ExecutionContextExecutor,
    materializer:     Materializer,
    log:              LoggingAdapter,
    settings:         RoutingSettings): RequestContext = wrap(delegate.reconfigure(executionContext, materializer, log, settings.asScala))

  def complete[T](value: T, marshaller: Marshaller[T, HttpResponse]): CompletionStage[RouteResult] = {
    delegate.complete(ToResponseMarshallable(value)(marshaller))
      .fast.map(r ⇒ r: RouteResult)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext).toJava
  }

  @varargs def reject(rejections: Rejection*): CompletionStage[RouteResult] = {
    val scalaRejections = rejections.map(_.asScala)
    delegate.reject(scalaRejections: _*)
      .fast.map(r ⇒ r.asJava: RouteResult)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext).toJava
  }

  def fail(error: Throwable): CompletionStage[RouteResult] =
    delegate.fail(error)
      .fast.map(r ⇒ r: RouteResult)(akka.dispatch.ExecutionContexts.sameThreadExecutionContext).toJava

  def withRequest(req: HttpRequest): RequestContext = wrap(delegate.withRequest(req.asScala))
  def withExecutionContext(ec: ExecutionContextExecutor): RequestContext = wrap(delegate.withExecutionContext(ec))
  def withMaterializer(materializer: Materializer): RequestContext = wrap(delegate.withMaterializer(materializer))
  def withLog(log: LoggingAdapter): RequestContext = wrap(delegate.withLog(log))
  def withRoutingSettings(settings: RoutingSettings): RequestContext = wrap(delegate.withRoutingSettings(settings.asScala))
  def withParserSettings(settings: ParserSettings): RequestContext = wrap(delegate.withParserSettings(settings.asScala))

  def mapRequest(f: JFunction[HttpRequest, HttpRequest]): RequestContext = wrap(delegate.mapRequest(r ⇒ f.apply(r.asJava).asScala))
  def withUnmatchedPath(path: String): RequestContext = wrap(delegate.withUnmatchedPath(Path(path)))
  def mapUnmatchedPath(f: JFunction[String, String]): RequestContext = wrap(delegate.mapUnmatchedPath(p ⇒ Path(f.apply(p.toString()))))
  def withAcceptAll: RequestContext = wrap(delegate.withAcceptAll)
}

object RequestContext {
  /** INTERNAL API */
  private[http] def wrap(delegate: scaladsl.server.RequestContext) = new RequestContext(delegate)
}
