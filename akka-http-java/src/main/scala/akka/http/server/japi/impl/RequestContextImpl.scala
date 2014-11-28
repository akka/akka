/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.model.japi.{ HttpResponse, StatusCodes }
import akka.http.model.japi.JavaMapping.Implicits._
import akka.http.model.japi
import akka.http.server.{ RequestContext ⇒ ScalaRequestContext }

import scala.concurrent.Future

/**
 * INTERNAL API
 */
private[japi] final case class RequestContextImpl(underlying: ScalaRequestContext) extends RequestContext {
  import underlying.executionContext

  // provides auto-conversion to japi.RouteResult
  import RouteResultImpl._

  def request: japi.HttpRequest = underlying.request
  def unmatchedPath: String = underlying.unmatchedPath.toString

  def completeWith(futureResult: Future[RouteResult]): RouteResult =
    futureResult.flatMap {
      case r: RouteResultImpl ⇒ r.underlying
    }
  def complete(text: String): RouteResult = underlying.complete(text)
  def completeWithStatus(statusCode: Int): RouteResult =
    completeWithStatus(StatusCodes.get(statusCode))
  def completeWithStatus(statusCode: japi.StatusCode): RouteResult =
    underlying.complete(statusCode.asScala)
  def completeAs[T](marshaller: Marshaller[T], value: T): RouteResult = marshaller match {
    case MarshallerImpl(m) ⇒
      implicit val marshaller = m(underlying.executionContext)
      underlying.complete(value)
    case _ ⇒ throw new IllegalArgumentException("Unsupported marshaller: $marshaller")
  }
  def complete(response: HttpResponse): RouteResult = underlying.complete(response.asScala)

  def notFound(): RouteResult = underlying.reject()
}
