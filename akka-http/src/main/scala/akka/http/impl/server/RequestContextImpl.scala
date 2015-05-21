/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import scala.concurrent.Future
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util.JavaMapping.Implicits._
import akka.http.scaladsl.server.{ RequestContext ⇒ ScalaRequestContext }
import akka.http.javadsl.server._

/**
 * INTERNAL API
 */
private[http] final case class RequestContextImpl(underlying: ScalaRequestContext) extends RequestContext {
  import underlying.executionContext

  // provides auto-conversion to japi.RouteResult
  import RouteResultImpl._

  def request: jm.HttpRequest = underlying.request
  def unmatchedPath: String = underlying.unmatchedPath.toString

  def completeWith(futureResult: Future[RouteResult]): RouteResult =
    futureResult.flatMap {
      case r: RouteResultImpl ⇒ r.underlying
    }
  def complete(text: String): RouteResult = underlying.complete(text)
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
}
