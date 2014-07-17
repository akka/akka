/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package impl

import akka.http.marshalling.{ ToResponseMarshallable, ToResponseMarshaller }
import akka.http.model.StatusCodes.Redirection
import akka.http.model.Uri.Path
import akka.http.model._

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

private[http] case class RequestContextImpl(request: HttpRequest, unmatchedPath: Uri.Path, postProcessing: RouteResult ⇒ RouteResult = identity) extends RequestContext {
  def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext = ???

  def withRouteResponseMappedPF(f: PartialFunction[RouteResult, RouteResult]): RequestContext = ???

  def withUnmatchedPathMapped(f: Path ⇒ Path): RequestContext = ???

  def withContentNegotiationDisabled: RequestContext = this // FIXME: actually support this

  def withRouteResponseRouting(f: PartialFunction[RouteResult, Route]): RequestContext =
    withRouteResponseHandling {
      case x if f.isDefinedAt(x) ⇒ f(x)(this)
    }

  def withHttpResponseEntityMapped(f: HttpEntity ⇒ HttpEntity): RequestContext = ???

  def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext = ???

  def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext = ???

  def withRouteResponseHandling(f: PartialFunction[RouteResult, RouteResult]): RequestContext =
    copy(postProcessing = { res ⇒
      val value = postProcessing(res)
      if (f.isDefinedAt(value)) f(value) else value
    })

  def withUnmatchedPath(path: Path): RequestContext = copy(unmatchedPath = path)

  def withExceptionHandling(handler: ExceptionHandler): RequestContext = ???

  def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext = ???

  def withRejectionsMapped(f: List[Rejection] ⇒ List[Rejection]): RequestContext =
    withRejectionHandling(rejs ⇒ Rejected(f(rejs)))

  def withRejectionHandling(f: List[Rejection] ⇒ RouteResult): RequestContext =
    withRouteResponseHandling {
      case Rejected(rejs) ⇒ f(rejs)
    }

  def complete(obj: ToResponseMarshallable): RouteResult =
    DeferredResult {
      implicit val ec = obj.executionContext
      obj.marshal
        .map(res ⇒ finish(CompleteWith(res)))
        .recover { case error ⇒ failWith(error) } // failWith already calls finish
    }

  def redirect(uri: Uri, redirectionType: Redirection): RouteResult = ???
  def reject(rejection: Rejection): RouteResult = finish(Rejected(rejection :: Nil))
  def reject(rejections: Rejection*): RouteResult = finish(Rejected(rejections.toList))
  def failWith(error: Throwable): RouteResult = finish(RouteException(error))
  def deferHandling(future: Future[RouteResult])(implicit ec: ExecutionContext): RouteResult =
    DeferredResult(future.map(finish).recover { case error ⇒ failWith(error) })

  def finish(result: RouteResult): RouteResult = postProcessing(result)
}
