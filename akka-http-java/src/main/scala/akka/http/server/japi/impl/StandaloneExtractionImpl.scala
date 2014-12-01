/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi
package impl

import akka.http.server._

import scala.concurrent.Future
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
private[japi] abstract class StandaloneExtractionImpl[T: ClassTag] extends ExtractionImpl[T] with RequestVal[T] {
  def directive: Directive1[T]
}

/**
 * INTERNAL API
 */
private[japi] abstract class ExtractingStandaloneExtractionImpl[T: ClassTag] extends StandaloneExtractionImpl[T] {
  def directive: Directive1[T] = Directives.extract(extract).flatMap(Directives.onSuccess(_))

  def extract(ctx: RequestContext): Future[T]
}
