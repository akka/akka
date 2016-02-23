/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import java.util.concurrent.CompletionStage

/**
 * A route Handler that handles a request (that is encapsulated in a [[RequestContext]])
 * and returns a [[RouteResult]] with the response (or the rejection).
 *
 * Use the methods in [[RequestContext]] to create a [[RouteResult]].
 * A handler MUST NOT return `null` as the result.
 *
 * See also [[Handler1]], [[Handler2]], ..., until [[Handler21]] for handling `N` request values.
 */
//#handler
trait Handler extends akka.japi.function.Function[RequestContext, RouteResult] {
  override def apply(ctx: RequestContext): RouteResult
}
//#handler

/**
 * A route Handler that handles a request (that is encapsulated in a [[RequestContext]])
 * and returns a [[java.util.concurrent.CompletionStage]] of [[RouteResult]] with the response (or the rejection).
 *
 * Use the methods in [[RequestContext]] to create a [[RouteResult]].
 * A handler MUST NOT return `null` as the result.
 */
trait AsyncHandler extends akka.japi.function.Function[RequestContext, CompletionStage[RouteResult]] {
  override def apply(ctx: RequestContext): CompletionStage[RouteResult]
}
