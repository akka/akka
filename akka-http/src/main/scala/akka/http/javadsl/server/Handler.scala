/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

/**
 * A route Handler that handles a request (that is encapsulated in a [[RequestContext]])
 * and returns a [[RouteResult]] with the response (or the rejection).
 *
 * Use the methods in [[RequestContext]] to create a [[RouteResult]]. A handler mustn't
 * return [[null]] as the result.
 */
trait Handler {
  def handle(ctx: RequestContext): RouteResult
}

/**
 * A route handler with one additional argument.
 */
trait Handler1[T1] {
  def handle(ctx: RequestContext, t1: T1): RouteResult
}

/**
 * A route handler with two additional arguments.
 */
trait Handler2[T1, T2] {
  def handle(ctx: RequestContext, t1: T1, t2: T2): RouteResult
}

/**
 * A route handler with three additional arguments.
 */
trait Handler3[T1, T2, T3] {
  def handle(ctx: RequestContext, t1: T1, t2: T2, t3: T3): RouteResult
}

/**
 * A route handler with four additional arguments.
 */
trait Handler4[T1, T2, T3, T4] {
  def handle(ctx: RequestContext, t1: T1, t2: T2, t3: T3, t4: T4): RouteResult
}