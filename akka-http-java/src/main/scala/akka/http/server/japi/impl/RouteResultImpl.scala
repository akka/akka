/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import scala.language.implicitConversions

import akka.http.server

import scala.concurrent.Future

/**
 * INTERNAL API
 */
private[japi] class RouteResultImpl(val underlying: Future[server.RouteResult]) extends RouteResult
/**
 * INTERNAL API
 */
private[japi] object RouteResultImpl {
  implicit def autoConvert(result: Future[server.RouteResult]): RouteResult =
    new RouteResultImpl(result)
}