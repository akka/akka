/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package impl

import akka.http.marshalling.ToResponseMarshallable
import akka.http.model.StatusCodes.Redirection
import akka.http.model.Uri.Path
import akka.http.model._
import akka.http.model.headers.Accept

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }

private[http] case class RequestContextImpl(request: HttpRequest, unmatchedPath: Uri.Path, postProcessing: RouteResult ⇒ RouteResult = identity) extends RequestContext {
  def withRequestMapped(f: HttpRequest ⇒ HttpRequest): RequestContext = copy(request = f(request))

  def withRequest(req: HttpRequest): RequestContext = copy(request = req)

  def withUnmatchedPathMapped(f: Path ⇒ Path): RequestContext = copy(unmatchedPath = f(unmatchedPath))

  def withContentNegotiationDisabled: RequestContext = copy(request = request.withHeaders(request.headers filterNot (_.isInstanceOf[Accept])))

  def withRouteResponseRouting(f: PartialFunction[RouteResult, Route]): RequestContext =
    withRouteResponseMappedPF {
      case x if f.isDefinedAt(x) ⇒ f(x)(this)
    }

  def withHttpResponseEntityMapped(f: HttpEntity ⇒ HttpEntity): RequestContext = withHttpResponseMapped(_.mapEntity(f))

  def withHttpResponseMapped(f: HttpResponse ⇒ HttpResponse): RequestContext =
    withRouteResponseMappedPF {
      case CompleteWith(response) ⇒ CompleteWith(f(response))
    }

  def withHttpResponseHeadersMapped(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestContext =
    withHttpResponseMapped(_.mapHeaders(f))

  def withRouteResponseHandling(f: PartialFunction[RouteResult, RouteResult]): RequestContext =
    copy(postProcessing = { res ⇒
      if (f.isDefinedAt(res)) f(res) else postProcessing(res)
    })

  def withUnmatchedPath(path: Path): RequestContext = copy(unmatchedPath = path)

  def withExceptionHandling(handler: ExceptionHandler): RequestContext = ???

  def withRouteResponseMapped(f: RouteResult ⇒ RouteResult): RequestContext =
    copy(postProcessing = res ⇒ postProcessing(f(res)))

  def withRouteResponseMappedPF(f: PartialFunction[RouteResult, RouteResult]): RequestContext =
    withRouteResponseMapped { res ⇒
      if (f.isDefinedAt(res)) f(res) else res
    }

  def withRejectionsMapped(f: List[Rejection] ⇒ List[Rejection]): RequestContext =
    withRouteResponseMappedPF {
      case Rejected(rejs) ⇒ Rejected(f(rejs))
    }

  def withRejectionHandling(f: List[Rejection] ⇒ RouteResult): RequestContext =
    withRouteResponseHandling {
      case Rejected(rejs) ⇒ f(rejs)
    }

  def complete(obj: ToResponseMarshallable): RouteResult =
    DeferredResult {
      implicit val ec = obj.executionContext
      obj.marshalFor(request)
        .map(res ⇒ finish(CompleteWith(res)))
        .recover {
          case RejectionError(rej) ⇒ reject(rej)
          case error               ⇒ failWith(error)
        }
    }

  def redirect(uri: Uri, redirectionType: Redirection)(implicit ec: ExecutionContext): RouteResult =
    complete {
      HttpResponse(
        status = redirectionType,
        headers = headers.Location(uri) :: Nil,
        entity = redirectionType.htmlTemplate match {
          case ""       ⇒ HttpEntity.Empty
          case template ⇒ HttpEntity(MediaTypes.`text/html`, template format uri)
        })
    }
  def reject(rejection: Rejection): RouteResult = finish(Rejected(rejection :: Nil))
  def reject(rejections: Rejection*): RouteResult = finish(Rejected(rejections.toList))
  def failWith(error: Throwable): RouteResult = finish(RouteException(error))
  def deferHandling(future: Future[RouteResult])(implicit ec: ExecutionContext): RouteResult =
    DeferredResult(future.map(finish).recover {
      case error ⇒ failWith(error)
    })

  def finish(result: RouteResult): RouteResult = postProcessing(result)
}
