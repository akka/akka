/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.{ Future, ExecutionContext }
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.FlowMaterializer
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture._

trait BasicDirectives {

  def mapInnerRoute(f: Route ⇒ Route): Directive0 =
    Directive { inner ⇒ f(inner(())) }

  def mapRequestContext(f: RequestContext ⇒ RequestContext): Directive0 =
    mapInnerRoute { inner ⇒ ctx ⇒ inner(f(ctx)) }

  def mapRequest(f: HttpRequest ⇒ HttpRequest): Directive0 =
    mapRequestContext(_ mapRequest f)

  def mapRouteResultFuture(f: Future[RouteResult] ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ f(inner(())(ctx)) }

  def mapRouteResult(f: RouteResult ⇒ RouteResult): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.map(f)(ctx.executionContext) }

  def mapRouteResultWith(f: RouteResult ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.flatMap(f)(ctx.executionContext) }

  def mapRouteResultPF(f: PartialFunction[RouteResult, RouteResult]): Directive0 =
    mapRouteResult(f.applyOrElse(_, akka.http.impl.util.identityFunc[RouteResult]))

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
  def mapUnmatchedPath(f: Uri.Path ⇒ Uri.Path): Directive0 =
    mapRequestContext(_ mapUnmatchedPath f)

  /**
   * Extracts the unmatched path from the RequestContext.
   */
  def extractUnmatchedPath: Directive1[Uri.Path] = BasicDirectives._extractUnmatchedPath

  /**
   * Extracts the complete request.
   */
  def extractRequest: Directive1[HttpRequest] = BasicDirectives._extractRequest

  /**
   * Extracts the complete request URI.
   */
  def extractUri: Directive1[Uri] = BasicDirectives._extractUri

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
   * Runs its inner route with the given alternative [[FlowMaterializer]].
   */
  def withFlowMaterializer(materializer: FlowMaterializer): Directive0 =
    mapRequestContext(_ withFlowMaterializer materializer)

  /**
   * Extracts the [[ExecutionContext]] from the [[RequestContext]].
   */
  def extractFlowMaterializer: Directive1[FlowMaterializer] = BasicDirectives._extractFlowMaterializer

  /**
   * Runs its inner route with the given alternative [[LoggingAdapter]].
   */
  def withLog(log: LoggingAdapter): Directive0 =
    mapRequestContext(_ withLog log)

  /**
   * Extracts the [[LoggingAdapter]] from the [[RequestContext]].
   */
  def extractLog: Directive1[LoggingAdapter] =
    BasicDirectives._extractLog

  /**
   * Runs its inner route with the given alternative [[RoutingSettings]].
   */
  def withSettings(settings: RoutingSettings): Directive0 =
    mapRequestContext(_ withSettings settings)

  /**
   * Runs the inner route with settings mapped by the given function.
   */
  def mapSettings(f: RoutingSettings ⇒ RoutingSettings): Directive0 =
    mapRequestContext(ctx ⇒ ctx.withSettings(f(ctx.settings)))

  /**
   * Extracts the [[RoutingSettings]] from the [[RequestContext]].
   */
  def extractSettings: Directive1[RoutingSettings] =
    BasicDirectives._extractSettings

  /**
   * Extracts the [[RequestContext]] itself.
   */
  def extractRequestContext: Directive1[RequestContext] = BasicDirectives._extractRequestContext
}

object BasicDirectives extends BasicDirectives {
  private val _extractUnmatchedPath: Directive1[Uri.Path] = extract(_.unmatchedPath)
  private val _extractRequest: Directive1[HttpRequest] = extract(_.request)
  private val _extractUri: Directive1[Uri] = extract(_.request.uri)
  private val _extractExecutionContext: Directive1[ExecutionContext] = extract(_.executionContext)
  private val _extractFlowMaterializer: Directive1[FlowMaterializer] = extract(_.flowMaterializer)
  private val _extractLog: Directive1[LoggingAdapter] = extract(_.log)
  private val _extractSettings: Directive1[RoutingSettings] = extract(_.settings)
  private val _extractRequestContext: Directive1[RequestContext] = extract(akka.http.impl.util.identityFunc)
}