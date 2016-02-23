/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import akka.http.javadsl.model.ContentType
import akka.http.javadsl.settings.{ RoutingSettings, ParserSettings }
import akka.http.scaladsl.model.HttpEntity
import akka.stream.Materializer
import scala.concurrent.{ ExecutionContextExecutor, Future }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.server.{ RequestContext ⇒ ScalaRequestContext }
import akka.http.javadsl.server._
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

/**
 * INTERNAL API
 */
private[http] final case class RequestContextImpl(underlying: ScalaRequestContext) extends RequestContext {
  // provides auto-conversion to japi.RouteResult
  import RouteResultImpl._

  def request: jm.HttpRequest = underlying.request
  def unmatchedPath: String = underlying.unmatchedPath.toString

  def completeWith(futureResult: Future[RouteResult]): RouteResult =
    futureResult.flatMap {
      case r: RouteResultImpl ⇒ r.underlying
    }(executionContext())
  def completeWith(futureResult: CompletionStage[RouteResult]): RouteResult = completeWith(futureResult.toScala)
  def complete(text: String): RouteResult = underlying.complete(text)
  def complete(contentType: ContentType.NonBinary, text: String): RouteResult =
    underlying.complete(HttpEntity(contentType.asScala, text))

  def completeWithStatus(statusCode: Int): RouteResult =
    completeWithStatus(jm.StatusCodes.get(statusCode))
  def completeWithStatus(statusCode: jm.StatusCode): RouteResult =
    underlying.complete(statusCode.asScala)
  def completeAs[T](marshaller: Marshaller[T], value: T): RouteResult = marshaller match {
    case MarshallerImpl(m) ⇒
      implicit val marshaller = m(underlying.executionContext)
      underlying.complete(value)
    case _ ⇒ throw new IllegalArgumentException(s"Unsupported marshaller: $marshaller")
  }
  def complete(response: jm.HttpResponse): RouteResult = underlying.complete(response.asScala)

  def notFound(): RouteResult = underlying.reject()

  def reject(customRejection: CustomRejection): RouteResult = underlying.reject(CustomRejectionWrapper(customRejection))

  def executionContext(): ExecutionContextExecutor = underlying.executionContext
  def materializer(): Materializer = underlying.materializer

  override def settings: RoutingSettings = underlying.settings
  override def parserSettings: ParserSettings = underlying.parserSettings
}
