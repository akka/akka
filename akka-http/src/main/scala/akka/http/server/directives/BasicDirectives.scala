/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.{ Future, ExecutionContext }
import scala.collection.immutable
import akka.http.server.util.Tuple
import akka.http.util.FastFuture
import akka.http.model._
import FastFuture._

trait BasicDirectives {

  def mapInnerRoute(f: Route ⇒ Route): Directive0 =
    Directive { inner ⇒ f(inner(())) }

  def mapRequestContext(f: RequestContext ⇒ RequestContext): Directive0 =
    mapInnerRoute { inner ⇒ ctx ⇒ inner(f(ctx)) }

  def mapRequest(f: HttpRequest ⇒ HttpRequest): Directive0 =
    mapRequestContext(_ withRequestMapped f)

  def mapRouteResultFuture(f: Future[RouteResult] ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ f(inner(())(ctx)) }

  def mapRouteResult(f: RouteResult ⇒ RouteResult): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.map(f)(ctx.executionContext) }

  def mapRouteResultWith(f: RouteResult ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.flatMap(f)(ctx.executionContext) }

  def mapRouteResultPF(f: PartialFunction[RouteResult, RouteResult]): Directive0 =
    mapRouteResult(f.applyOrElse(_, akka.http.util.identityFunc[RouteResult]))

  def mapRouteResultWithPF(f: PartialFunction[RouteResult, Future[RouteResult]]): Directive0 =
    mapRouteResultWith(f.applyOrElse(_, FastFuture.successful[RouteResult]))

  def recoverRejections(f: immutable.Seq[Rejection] ⇒ RouteResult): Directive0 =
    mapRouteResultPF { case RouteResult.Rejected(rejections) ⇒ f(rejections) }

  def recoverRejectionsWith(f: immutable.Seq[Rejection] ⇒ Future[RouteResult]): Directive0 =
    mapRouteResultWithPF { case RouteResult.Rejected(rejections) ⇒ f(rejections) }

  def mapRejections(f: immutable.Seq[Rejection] ⇒ immutable.Seq[Rejection]): Directive0 =
    recoverRejections(rejections ⇒ RouteResult.Rejected(f(rejections)))

  def mapResponse(f: HttpResponse ⇒ HttpResponse): Directive0 =
    mapRouteResultPF { case RouteResult.Complete(response) ⇒ RouteResult.Complete(f(response)) }

  def mapResponseEntity(f: ResponseEntity ⇒ ResponseEntity): Directive0 =
    mapResponse(_ mapEntity f)

  def mapResponseHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): Directive0 =
    mapResponse(_ mapHeaders f)

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
  def tprovide[L: Tuple](values: L): Directive[L] =
    Directive { _(values) }

  /**
   * Extracts a single value using the given function.
   */
  def extract[T](f: RequestContext ⇒ T): Directive1[T] =
    textract(ctx ⇒ Tuple1(f(ctx)))

  /**
   * Extracts a number of values using the given function.
   */
  def textract[L: Tuple](f: RequestContext ⇒ L): Directive[L] =
    Directive { inner ⇒ ctx ⇒ inner(f(ctx))(ctx) }

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

  /**
   * Runs its inner route with the given alternative [[ExecutionContext]].
   */
  def withExecutionContext(ec: ExecutionContext): Directive0 =
    mapRequestContext(_ withExecutionContext ec)

  /**
   * Extracts the [[ExecutionContext]] from the [[RequestContext]].
   */
  def extractExecutionContext: Directive1[ExecutionContext] = BasicDirectives._extractExecutionContext

  /**
   * Extracts the [[RequestContext]] itself.
   */
  def extractRequestContext: Directive1[RequestContext] = BasicDirectives._extractRequestContext
}

object BasicDirectives extends BasicDirectives {
  private val _unmatchedPath: Directive1[Uri.Path] = extract(_.unmatchedPath)
  private val _requestInstance: Directive1[HttpRequest] = extract(_.request)
  private val _requestUri: Directive1[Uri] = extract(_.request.uri)
  private val _extractExecutionContext: Directive1[ExecutionContext] = extract(_.executionContext)
  private val _extractRequestContext: Directive1[RequestContext] = extract(akka.http.util.identityFunc)
}