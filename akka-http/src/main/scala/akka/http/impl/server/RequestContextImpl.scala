/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.model.ContentType
import akka.http.scaladsl.model.HttpEntity
import akka.stream.Materializer

import scala.concurrent.{ ExecutionContext, Future }
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.server.{ RequestContext ⇒ ScalaRequestContext }
import akka.http.javadsl.server._

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
  def complete(text: String): RouteResult = underlying.complete(text)
  def complete(contentType: ContentType, text: String): RouteResult =
    underlying.complete(HttpEntity(contentType.asScala, text))

  def completeWithStatus(statusCode: Int): RouteResult =
    completeWithStatus(jm.StatusCodes.get(statusCode))
  def completeWithStatus(statusCode: jm.StatusCode): RouteResult =
    underlying.complete(statusCode.asScala)
  def completeAs[T](marshaller: Marshaller[T], value: T): RouteResult = marshaller match {
    case MarshallerImpl(m) ⇒
      implicit val marshaller = m(underlying.executionContext)
      underlying.complete(value)
    case _ ⇒ throw new IllegalArgumentException("Unsupported marshaller: $marshaller")
  }
  def complete(response: jm.HttpResponse): RouteResult = underlying.complete(response.asScala)

  def notFound(): RouteResult = underlying.reject()

  def executionContext(): ExecutionContext = underlying.executionContext
  def materializer(): Materializer = underlying.materializer
}
