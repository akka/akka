/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.directives

import scala.annotation.varargs
import java.lang.reflect.Method

import akka.http.javadsl.model.{ StatusCode, HttpResponse }
import akka.http.javadsl.server._
import akka.http.impl.server.RouteStructure._
import akka.http.impl.server._

abstract class BasicDirectives {
  /**
   * Tries the given routes in sequence until the first one matches.
   */
  @varargs
  def route(route: Route, others: Route*): Route =
    RouteAlternatives(route +: others.toVector)

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
    Directives.custom(Extract(extractions.map(_.asInstanceOf[StandaloneExtractionImpl[_ <: AnyRef]]), _))

  /**
   * A route that handles the request with the given opaque handler. Specify a set of extractions
   * that will be used in the handler to make sure they are available.
   */
  @varargs
  def handleWith[T1](handler: Handler, extractions: RequestVal[_]*): Route =
    handle(extractions: _*)(handler.handle(_))

  /**
   * A route that handles the request given the value of a single [[RequestVal]].
   */
  def handleWith[T1](e1: RequestVal[T1], handler: Handler1[T1]): Route =
    handle(e1)(ctx ⇒ handler.handle(ctx, e1.get(ctx)))

  /**
   * A route that handles the request given the values of the given [[RequestVal]]s.
   */
  def handleWith[T1, T2](e1: RequestVal[T1], e2: RequestVal[T2], handler: Handler2[T1, T2]): Route =
    handle(e1, e2)(ctx ⇒ handler.handle(ctx, e1.get(ctx), e2.get(ctx)))

  /**
   * A route that handles the request given the values of the given [[RequestVal]]s.
   */
  def handleWith[T1, T2, T3](
    e1: RequestVal[T1], e2: RequestVal[T2], e3: RequestVal[T3], handler: Handler3[T1, T2, T3]): Route =
    handle(e1, e2, e3)(ctx ⇒ handler.handle(ctx, e1.get(ctx), e2.get(ctx), e3.get(ctx)))

  /**
   * A route that handles the request given the values of the given [[RequestVal]]s.
   */
  def handleWith[T1, T2, T3, T4](
    e1: RequestVal[T1], e2: RequestVal[T2], e3: RequestVal[T3], e4: RequestVal[T4], handler: Handler4[T1, T2, T3, T4]): Route =
    handle(e1, e2, e3, e4)(ctx ⇒ handler.handle(ctx, e1.get(ctx), e2.get(ctx), e3.get(ctx), e4.get(ctx)))

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
  def handleWith(instance: AnyRef, methodName: String, extractions: RequestVal[_]*): Route =
    handleWith(instance.getClass, instance, methodName, extractions: _*)

  /**
   * Handles the route by reflectively calling the static method specified by `clazz`, and `methodName`.
   * Additionally, the value of all extractions will be passed to the function.
   *
   * For extraction types `Extraction[T1]`, `Extraction[T2]`, ... the shape of the method must match this pattern:
   *
   * public static RouteResult methodName(RequestContext ctx, T1 t1, T2 t2, ...)
   */
  @varargs
  def handleWith(clazz: Class[_], methodName: String, extractions: RequestVal[_]*): Route =
    handleWith(clazz, null, methodName, extractions: _*)

  /**
   * Handles the route by calling the method specified by `clazz`, `instance`, and `methodName`. Additionally, the value
   * of all extractions will be passed to the function.
   *
   * For extraction types `Extraction[T1]`, `Extraction[T2]`, ... the shape of the method must match this pattern:
   *
   * public static RouteResult methodName(RequestContext ctx, T1 t1, T2 t2, ...)
   */
  @varargs
  def handleWith(clazz: Class[_], instance: AnyRef, methodName: String, extractions: RequestVal[_]*): Route = {
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
        method.getReturnType == classOf[RouteResult]

      object ParameterTypes {
        def unapply(method: Method): Option[List[Class[_]]] = Some(method.getParameterTypes.toList)
      }

      methods.filter(returnTypeMatches).collectFirst {
        case method @ ParameterTypes(RequestContextClass :: rest) if paramsMatch(rest) ⇒ {
          if (!method.isAccessible) method.setAccessible(true) // FIXME: test what happens if this fails
          (ctx: RequestContext, params: Seq[Any]) ⇒ method.invoke(instance, (ctx +: params).toArray.asInstanceOf[Array[AnyRef]]: _*).asInstanceOf[RouteResult]
        }

        case method @ ParameterTypes(rest) if paramsMatch(rest) ⇒ {
          if (!method.isAccessible) method.setAccessible(true)
          (ctx: RequestContext, params: Seq[Any]) ⇒ method.invoke(instance, params.toArray.asInstanceOf[Array[AnyRef]]: _*).asInstanceOf[RouteResult]
        }
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