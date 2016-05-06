/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.{ Future, ExecutionContextExecutor }
import scala.collection.immutable
import akka.event.LoggingAdapter
import akka.stream.Materializer
import akka.http.scaladsl.settings.{ RoutingSettings, ParserSettings }
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture._

/**
 * @groupname basic Basic directives
 * @groupprio basic 10
 */
trait BasicDirectives {

  /**
   * @group basic
   */
  def mapInnerRoute(f: Route ⇒ Route): Directive0 =
    Directive { inner ⇒ f(inner(())) }

  /**
   * @group basic
   */
  def mapRequestContext(f: RequestContext ⇒ RequestContext): Directive0 =
    mapInnerRoute { inner ⇒ ctx ⇒ inner(f(ctx)) }

  /**
   * @group basic
   */
  def mapRequest(f: HttpRequest ⇒ HttpRequest): Directive0 =
    mapRequestContext(_ mapRequest f)

  /**
   * @group basic
   */
  def mapRouteResultFuture(f: Future[RouteResult] ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ f(inner(())(ctx)) }

  /**
   * @group basic
   */
  def mapRouteResult(f: RouteResult ⇒ RouteResult): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.map(f)(ctx.executionContext) }

  /**
   * @group basic
   */
  def mapRouteResultWith(f: RouteResult ⇒ Future[RouteResult]): Directive0 =
    Directive { inner ⇒ ctx ⇒ inner(())(ctx).fast.flatMap(f)(ctx.executionContext) }

  /**
   * @group basic
   */
  def mapRouteResultPF(f: PartialFunction[RouteResult, RouteResult]): Directive0 =
    mapRouteResult(f.applyOrElse(_, conforms[RouteResult]))

  /**
   * @group basic
   */
  def mapRouteResultWithPF(f: PartialFunction[RouteResult, Future[RouteResult]]): Directive0 =
    mapRouteResultWith(f.applyOrElse(_, FastFuture.successful[RouteResult]))

  /**
   * @group basic
   */
  def recoverRejections(f: immutable.Seq[Rejection] ⇒ RouteResult): Directive0 =
    mapRouteResultPF { case RouteResult.Rejected(rejections) ⇒ f(rejections) }

  /**
   * @group basic
   */
  def recoverRejectionsWith(f: immutable.Seq[Rejection] ⇒ Future[RouteResult]): Directive0 =
    mapRouteResultWithPF { case RouteResult.Rejected(rejections) ⇒ f(rejections) }

  /**
   * @group basic
   */
  def mapRejections(f: immutable.Seq[Rejection] ⇒ immutable.Seq[Rejection]): Directive0 =
    recoverRejections(rejections ⇒ RouteResult.Rejected(f(rejections)))

  /**
   * @group basic
   */
  def mapResponse(f: HttpResponse ⇒ HttpResponse): Directive0 =
    mapRouteResultPF { case RouteResult.Complete(response) ⇒ RouteResult.Complete(f(response)) }

  /**
   * @group basic
   */
  def mapResponseEntity(f: ResponseEntity ⇒ ResponseEntity): Directive0 =
    mapResponse(_ mapEntity f)

  /**
   * @group basic
   */
  def mapResponseHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): Directive0 =
    mapResponse(_ mapHeaders f)

  /**
   * A Directive0 that always passes the request on to its inner route
   * (i.e. does nothing with the request or the response).
   *
   * @group basic
   */
  def pass: Directive0 = Directive.Empty

  /**
   * Injects the given value into a directive.
   *
   * @group basic
   */
  def provide[T](value: T): Directive1[T] = tprovide(Tuple1(value))

  /**
   * Injects the given values into a directive.
   *
   * @group basic
   */
  def tprovide[L: Tuple](values: L): Directive[L] =
    Directive { _(values) }

  /**
   * Extracts a single value using the given function.
   *
   * @group basic
   */
  def extract[T](f: RequestContext ⇒ T): Directive1[T] =
    textract(ctx ⇒ Tuple1(f(ctx)))

  /**
   * Extracts a number of values using the given function.
   *
   * @group basic
   */
  def textract[L: Tuple](f: RequestContext ⇒ L): Directive[L] =
    Directive { inner ⇒ ctx ⇒ inner(f(ctx))(ctx) }

  /**
   * Adds a TransformationRejection cancelling all rejections equal to the given one
   * to the list of rejections potentially coming back from the inner route.
   *
   * @group basic
   */
  def cancelRejection(rejection: Rejection): Directive0 =
    cancelRejections(_ == rejection)

  /**
   * Adds a TransformationRejection cancelling all rejections of one of the given classes
   * to the list of rejections potentially coming back from the inner route.
   *
   * @group basic
   */
  def cancelRejections(classes: Class[_]*): Directive0 =
    cancelRejections(r ⇒ classes.exists(_ isInstance r))

  /**
   * Adds a TransformationRejection cancelling all rejections for which the given filter function returns true
   * to the list of rejections potentially coming back from the inner route.
   *
   * @group basic
   */
  def cancelRejections(cancelFilter: Rejection ⇒ Boolean): Directive0 =
    mapRejections(_ :+ TransformationRejection(_ filterNot cancelFilter))

  /**
   * Transforms the unmatchedPath of the RequestContext using the given function.
   *
   * @group basic
   */
  def mapUnmatchedPath(f: Uri.Path ⇒ Uri.Path): Directive0 =
    mapRequestContext(_ mapUnmatchedPath f)

  /**
   * Extracts the yet unmatched path from the RequestContext.
   *
   * @group basic
   */
  def extractUnmatchedPath: Directive1[Uri.Path] = BasicDirectives._extractUnmatchedPath

  /**
   * Extracts the current [[HttpRequest]] instance.
   *
   * @group basic
   */
  def extractRequest: Directive1[HttpRequest] = BasicDirectives._extractRequest

  /**
   * Extracts the complete request URI.
   *
   * @group basic
   */
  def extractUri: Directive1[Uri] = BasicDirectives._extractUri

  /**
   * Runs its inner route with the given alternative [[scala.concurrent.ExecutionContextExecutor]].
   *
   * @group basic
   */
  def withExecutionContext(ec: ExecutionContextExecutor): Directive0 =
    mapRequestContext(_ withExecutionContext ec)

  /**
   * Extracts the [[scala.concurrent.ExecutionContextExecutor]] from the [[akka.http.scaladsl.server.RequestContext]].
   *
   * @group basic
   */
  def extractExecutionContext: Directive1[ExecutionContextExecutor] = BasicDirectives._extractExecutionContext

  /**
   * Runs its inner route with the given alternative [[akka.stream.Materializer]].
   *
   * @group basic
   */
  def withMaterializer(materializer: Materializer): Directive0 =
    mapRequestContext(_ withMaterializer materializer)

  /**
   * Extracts the [[akka.stream.Materializer]] from the [[akka.http.scaladsl.server.RequestContext]].
   *
   * @group basic
   */
  def extractMaterializer: Directive1[Materializer] = BasicDirectives._extractMaterializer

  /**
   * Runs its inner route with the given alternative [[akka.event.LoggingAdapter]].
   *
   * @group basic
   */
  def withLog(log: LoggingAdapter): Directive0 =
    mapRequestContext(_ withLog log)

  /**
   * Extracts the [[akka.event.LoggingAdapter]] from the [[akka.http.scaladsl.server.RequestContext]].
   *
   * @group basic
   */
  def extractLog: Directive1[LoggingAdapter] =
    BasicDirectives._extractLog

  /**
   * Runs its inner route with the given alternative [[RoutingSettings]].
   *
   * @group basic
   */
  def withSettings(settings: RoutingSettings): Directive0 =
    mapRequestContext(_ withRoutingSettings settings)

  /**
   * Runs the inner route with settings mapped by the given function.
   *
   * @group basic
   */
  def mapSettings(f: RoutingSettings ⇒ RoutingSettings): Directive0 =
    mapRequestContext(ctx ⇒ ctx.withRoutingSettings(f(ctx.settings)))

  /**
   * Extracts the [[RoutingSettings]] from the [[akka.http.scaladsl.server.RequestContext]].
   *
   * @group basic
   */
  def extractSettings: Directive1[RoutingSettings] =
    BasicDirectives._extractSettings

  /**
   * Extracts the [[akka.http.scaladsl.settings.ParserSettings]] from the [[akka.http.scaladsl.server.RequestContext]].
   *
   * @group basic
   */
  def extractParserSettings: Directive1[ParserSettings] =
    BasicDirectives._extractParserSettings

  /**
   * Extracts the [[akka.http.scaladsl.server.RequestContext]] itself.
   *
   * @group basic
   */
  def extractRequestContext: Directive1[RequestContext] = BasicDirectives._extractRequestContext
}

object BasicDirectives extends BasicDirectives {
  private val _extractUnmatchedPath: Directive1[Uri.Path] = extract(_.unmatchedPath)
  private val _extractRequest: Directive1[HttpRequest] = extract(_.request)
  private val _extractUri: Directive1[Uri] = extract(_.request.uri)
  private val _extractExecutionContext: Directive1[ExecutionContextExecutor] = extract(_.executionContext)
  private val _extractMaterializer: Directive1[Materializer] = extract(_.materializer)
  private val _extractLog: Directive1[LoggingAdapter] = extract(_.log)
  private val _extractSettings: Directive1[RoutingSettings] = extract(_.settings)
  private val _extractParserSettings: Directive1[ParserSettings] = extract(_.parserSettings)
  private val _extractRequestContext: Directive1[RequestContext] = extract(conforms)
}
