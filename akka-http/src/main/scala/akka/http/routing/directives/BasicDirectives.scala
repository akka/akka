/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing
package directives

import scala.collection.immutable
import akka.http.routing.util.Tuple
import akka.http.model._

trait BasicDirectives {

  def mapInnerRoute(f: Route ⇒ Route): Directive0 = new Directive0 {
    def tapply(inner: Unit ⇒ Route) = f(inner(()))
  }

  def mapRequestContext(f: RequestContext ⇒ RequestContext): Directive0 =
    mapInnerRoute { inner ⇒ ctx ⇒ inner(f(ctx)) }

  def mapRequest(f: HttpRequest ⇒ HttpRequest): Directive0 =
    mapRequestContext(_ withRequestMapped f)

  def mapRouteResponse(f: RouteResult ⇒ RouteResult): Directive0 =
    mapRequestContext(_ withRouteResponseMapped f)

  def mapRouteResponsePF(f: PartialFunction[RouteResult, RouteResult]): Directive0 =
    mapRequestContext(_ withRouteResponseMappedPF f)

  def mapRejections(f: List[Rejection] ⇒ List[Rejection]): Directive0 =
    mapRequestContext(_ withRejectionsMapped f)

  def mapHttpResponse(f: HttpResponse ⇒ HttpResponse): Directive0 =
    mapRequestContext(_ withHttpResponseMapped f)

  def mapHttpResponseEntity(f: HttpEntity ⇒ HttpEntity): Directive0 =
    mapRequestContext(_ withHttpResponseEntityMapped f)

  def mapHttpResponseHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): Directive0 =
    mapRequestContext(_ withHttpResponseHeadersMapped f)

  /**
   * A Directive0 that always passes the request on to its inner route
   * (i.e. does nothing with the request or the response).
   */
  def pass: Directive0 = Directive.Empty

  /**
   * Injects the given value into a directive.
   */
  def provide[T](value: T): Directive1[T] = tprovide(Tuple1(value))

  /**
   * Injects the given values into a directive.
   */
  def tprovide[L: Tuple](values: L): Directive[L] = new Directive[L] {
    def tapply(f: L ⇒ Route) = f(values)
  }

  /**
   * Extracts a single value using the given function.
   */
  def extract[T](f: RequestContext ⇒ T): Directive1[T] =
    textract(ctx ⇒ Tuple1(f(ctx)))

  /**
   * Extracts a number of values using the given function.
   */
  def textract[L: Tuple](f: RequestContext ⇒ L): Directive[L] = new Directive[L] {
    def tapply(inner: L ⇒ Route) = ctx ⇒ inner(f(ctx))(ctx)
  }

  /**
   * Adds a TransformationRejection cancelling all rejections equal to the given one
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejection(rejection: Rejection): Directive0 =
    cancelRejections(_ == rejection)

  /**
   * Adds a TransformationRejection cancelling all rejections of one of the given classes
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(classes: Class[_]*): Directive0 =
    cancelRejections(r ⇒ classes.exists(_ isInstance r))

  /**
   * Adds a TransformationRejection cancelling all rejections for which the given filter function returns true
   * to the list of rejections potentially coming back from the inner route.
   */
  def cancelRejections(cancelFilter: Rejection ⇒ Boolean): Directive0 =
    mapRejections(_ :+ TransformationRejection(_ filterNot cancelFilter))

  /**
   * Transforms the unmatchedPath of the RequestContext using the given function.
   */
  def rewriteUnmatchedPath(f: Uri.Path ⇒ Uri.Path): Directive0 =
    mapRequestContext(_ withUnmatchedPathMapped f)

  /**
   * Extracts the unmatched path from the RequestContext.
   */
  def unmatchedPath: Directive1[Uri.Path] = BasicDirectives._unmatchedPath

  /**
   * Extracts the complete request.
   */
  def requestInstance: Directive1[HttpRequest] = BasicDirectives._requestInstance

  /**
   * Extracts the complete request URI.
   */
  def requestUri: Directive1[Uri] = BasicDirectives._requestUri
}

object BasicDirectives extends BasicDirectives {
  private val _unmatchedPath: Directive1[Uri.Path] = extract(_.unmatchedPath)
  private val _requestInstance: Directive1[HttpRequest] = extract(_.request)
  private val _requestUri: Directive1[Uri] = extract(_.request.uri)
}