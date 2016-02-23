/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.lang.reflect.{ Method, ParameterizedType }

import akka.http.impl.server.RouteStructure._
import akka.http.impl.server._
import akka.http.javadsl.model.{ ContentType, HttpResponse, StatusCode, Uri }
import akka.http.javadsl.server._

import scala.annotation.varargs
import scala.concurrent.Future
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

abstract class BasicDirectives extends BasicDirectivesBase {
  /**
   * Tries the given route alternatives in sequence until the first one matches.
   */
  @varargs
  def route(innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteAlternatives()(innerRoute, moreInnerRoutes.toList)

  /**
   * A route that completes the request with a static text
   */
  def complete(text: String): Route =
    new OpaqueRoute() {
      def handle(ctx: RequestContext): RouteResult = ctx.complete(text)
    }

  /**
   * A route that completes the request with a static text
   */
  def complete(contentType: ContentType.NonBinary, text: String): Route =
    new OpaqueRoute() {
      def handle(ctx: RequestContext): RouteResult =
        ctx.complete(contentType, text)
    }

  /**
   * A route that completes the request with a static text
   */
  def complete(response: HttpResponse): Route =
    new OpaqueRoute() {
      def handle(ctx: RequestContext): RouteResult = ctx.complete(response)
    }

  /**
   * A route that completes the request with a status code.
   */
  def completeWithStatus(code: StatusCode): Route =
    new OpaqueRoute() {
      def handle(ctx: RequestContext): RouteResult = ctx.completeWithStatus(code)
    }

  /**
   * A route that completes the request using the given marshaller and value.
   */
  def completeAs[T](marshaller: Marshaller[T], value: T): Route =
    new OpaqueRoute() {
      def handle(ctx: RequestContext): RouteResult = ctx.completeAs(marshaller, value)
    }

  /**
   * Completes the request with redirection response of the given type to the given URI.
   *
   * The `redirectionType` must be a StatusCode for which `isRedirection` returns true.
   */
  def redirect(uri: Uri, redirectionType: StatusCode): Route = Redirect(uri, redirectionType)

  /**
   * A route that extracts a value and completes the request with it.
   */
  def extractAndComplete[T](marshaller: Marshaller[T], extraction: RequestVal[T]): Route =
    handle(extraction)(ctx ⇒ ctx.completeAs(marshaller, extraction.get(ctx)))

  /**
   * A directive that makes sure that all the standalone extractions have been
   * executed and validated.
   */
  @varargs
  def extractHere(extractions: RequestVal[_]*): Directive =
    Directives.custom(Extract(extractions.map(_.asInstanceOf[StandaloneExtractionImpl[_ <: AnyRef]])))

  private[http] def handle(extractions: RequestVal[_]*)(f: RequestContext ⇒ RouteResult): Route = {
    val route =
      new OpaqueRoute() {
        def handle(ctx: RequestContext): RouteResult = f(ctx)
      }
    val saExtractions = extractions.collect { case sa: StandaloneExtractionImpl[_] ⇒ sa }
    if (saExtractions.isEmpty) route
    else extractHere(saExtractions: _*).route(route)
  }

  /**
   * Handles the route by reflectively calling the instance method specified by `instance`, and `methodName`.
   * Additionally, the value of all extractions will be passed to the function.
   *
   * For extraction types `Extraction[T1]`, `Extraction[T2]`, ... the shape of the method must match this pattern:
   *
   * public static RouteResult methodName(RequestContext ctx, T1 t1, T2 t2, ...)
   */
  @varargs
  def handleReflectively(instance: AnyRef, methodName: String, extractions: RequestVal[_]*): Route =
    handleReflectively(instance.getClass, instance, methodName, extractions: _*)

  /**
   * Handles the route by reflectively calling the static method specified by `clazz`, and `methodName`.
   * Additionally, the value of all extractions will be passed to the function.
   *
   * For extraction types `Extraction[T1]`, `Extraction[T2]`, ... the shape of the method must match this pattern:
   *
   * public static RouteResult methodName(RequestContext ctx, T1 t1, T2 t2, ...)
   */
  @varargs
  def handleReflectively(clazz: Class[_], methodName: String, extractions: RequestVal[_]*): Route =
    handleReflectively(clazz, null, methodName, extractions: _*)

  /**
   * Handles the route by calling the method specified by `clazz`, `instance`, and `methodName`. Additionally, the value
   * of all extractions will be passed to the function.
   *
   * For extraction types `Extraction[T1]`, `Extraction[T2]`, ... the shape of the method must match this pattern:
   *
   * public static RouteResult methodName(RequestContext ctx, T1 t1, T2 t2, ...)
   */
  @varargs
  def handleReflectively(clazz: Class[_], instance: AnyRef, methodName: String, extractions: RequestVal[_]*): Route = {
    def chooseOverload(methods: Seq[Method]): (RequestContext, Seq[Any]) ⇒ RouteResult = {
      val extractionTypes = extractions.map(_.resultClass).toList
      val RequestContextClass = classOf[RequestContext]

      import java.{ lang ⇒ jl }
      def paramMatches(expected: Class[_], actual: Class[_]): Boolean = expected match {
        case e if e isAssignableFrom actual ⇒ true
        case jl.Long.TYPE if actual == classOf[jl.Long] ⇒ true
        case jl.Integer.TYPE if actual == classOf[jl.Integer] ⇒ true
        case jl.Short.TYPE if actual == classOf[jl.Short] ⇒ true
        case jl.Character.TYPE if actual == classOf[jl.Character] ⇒ true
        case jl.Byte.TYPE if actual == classOf[jl.Byte] ⇒ true
        case jl.Double.TYPE if actual == classOf[jl.Double] ⇒ true
        case jl.Float.TYPE if actual == classOf[jl.Float] ⇒ true
        case _ ⇒ false
      }
      def paramsMatch(params: Seq[Class[_]]): Boolean = {
        val res =
          params.size == extractionTypes.size &&
            (params, extractionTypes).zipped.forall(paramMatches)

        res
      }
      def returnTypeMatches(method: Method): Boolean =
        method.getReturnType == classOf[RouteResult] || returnsFuture(method) || returnsCompletionStage(method)

      def returnsFuture(method: Method): Boolean =
        method.getReturnType == classOf[Future[_]] &&
          method.getGenericReturnType.isInstanceOf[ParameterizedType] &&
          method.getGenericReturnType.asInstanceOf[ParameterizedType].getActualTypeArguments()(0) == classOf[RouteResult]

      def returnsCompletionStage(method: Method): Boolean =
        method.getReturnType == classOf[CompletionStage[_]] &&
          method.getGenericReturnType.isInstanceOf[ParameterizedType] &&
          method.getGenericReturnType.asInstanceOf[ParameterizedType].getActualTypeArguments()(0) == classOf[RouteResult]

      /** Makes sure both RouteResult and Future[RouteResult] are acceptable result types. */
      def adaptResult(method: Method): (RequestContext, AnyRef) ⇒ RouteResult =
        if (returnsFuture(method)) (ctx, v) ⇒ ctx.completeWith(v.asInstanceOf[Future[RouteResult]].toJava)
        else if (returnsCompletionStage(method)) (ctx, v) => ctx.completeWith(v.asInstanceOf[CompletionStage[RouteResult]])
        else (_, v) ⇒ v.asInstanceOf[RouteResult]

      val IdentityAdaptor: (RequestContext, Seq[Any]) ⇒ Seq[Any] = (_, ps) ⇒ ps
      def methodInvocator(method: Method, adaptParams: (RequestContext, Seq[Any]) ⇒ Seq[Any]): (RequestContext, Seq[Any]) ⇒ RouteResult = {
        val resultAdaptor = adaptResult(method)
        if (!method.isAccessible) method.setAccessible(true)
        if (adaptParams == IdentityAdaptor)
          (ctx, params) ⇒ resultAdaptor(ctx, method.invoke(instance, params.toArray.asInstanceOf[Array[AnyRef]]: _*))
        else
          (ctx, params) ⇒ resultAdaptor(ctx, method.invoke(instance, adaptParams(ctx, params).toArray.asInstanceOf[Array[AnyRef]]: _*))
      }

      object ParameterTypes {
        def unapply(method: Method): Option[List[Class[_]]] = Some(method.getParameterTypes.toList)
      }

      methods.filter(returnTypeMatches).collectFirst {
        case method @ ParameterTypes(RequestContextClass :: rest) if paramsMatch(rest) ⇒ methodInvocator(method, _ +: _)
        case method @ ParameterTypes(rest) if paramsMatch(rest)                        ⇒ methodInvocator(method, IdentityAdaptor)
      }.getOrElse(throw new RuntimeException("No suitable method found"))
    }
    def lookupMethod() = {
      val candidateMethods = clazz.getMethods.filter(_.getName == methodName)
      chooseOverload(candidateMethods)
    }

    val method = lookupMethod()

    handle(extractions: _*)(ctx ⇒ method(ctx, extractions.map(_.get(ctx))))
  }
}
