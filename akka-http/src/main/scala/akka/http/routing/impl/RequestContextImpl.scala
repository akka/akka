/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package impl

import akka.http.marshalling.ToResponseMarshaller
import akka.http.model.StatusCodes.Redirection
import akka.http.model.Uri.Path
import akka.http.model._

import scala.collection.immutable
import scala.concurrent.Future

private[http] case class RequestContextImpl(request: HttpRequest, unmatchedPath: Uri.Path, postProcessing: RouteResult ⇒ RouteResult = identity) extends RequestContext {
  def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext = ???

  def withRouteResponseMappedPF(f: PartialFunction[RouteResult, RouteResult]): RequestContext = ???

  def withUnmatchedPathMapped(f: Path ⇒ Path): RequestContext = ???

  def withContentNegotiationDisabled: RequestContext = this // FIXME: actually support this

  def withRouteResponseRouting(f: PartialFunction[RouteResult, Route]): RequestContext = ???

  def withHttpResponseEntityMapped(f: HttpEntity ⇒ HttpEntity): RequestContext = ???

  def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext = ???

  def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext = ???

  def withRouteResponseHandling(f: PartialFunction[RouteResult, RouteResult]): RequestContext =
    copy(postProcessing = { res ⇒
      val value = postProcessing(res)
      if (f.isDefinedAt(value)) f(value) else value
    })

  def withUnmatchedPath(path: Path): RequestContext = ???

  def withExceptionHandling(handler: ExceptionHandler): RequestContext = ???

  def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext = ???

  def withRejectionsMapped(f: List[Rejection] ⇒ List[Rejection]): RequestContext =
    withRejectionHandling(rejs ⇒ Rejected(f(rejs)))

  def withRejectionHandling(f: List[Rejection] ⇒ RouteResult): RequestContext =
    withRouteResponseHandling {
      case Rejected(rejs) ⇒ f(rejs)
    }

  def complete[T](obj: T)(implicit marshaller: ToResponseMarshaller[T]): RouteResult =
    // FIXME: add content-negotiation
    marshaller.marshal(obj) match {
      case Right(res)  ⇒ finish(CompleteWith(res))
      case Left(error) ⇒ failWith(error)
    }

  def redirect(uri: Uri, redirectionType: Redirection): RouteResult = ???
  def reject(rejection: Rejection): RouteResult = finish(Rejected(rejection :: Nil))
  def reject(rejections: Rejection*): RouteResult = finish(Rejected(rejections.toList))
  def failWith(error: Throwable): RouteResult = ???
  def deferHandling(future: Future[RouteResult]): RouteResult = ???

  def finish(result: RouteResult): RouteResult = postProcessing(result)
}
