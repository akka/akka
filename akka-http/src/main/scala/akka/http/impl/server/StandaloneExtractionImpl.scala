/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.concurrent.Future
import scala.reflect.ClassTag
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.server._

/**
 * INTERNAL API
 */
private[http] abstract class StandaloneExtractionImpl[T: ClassTag] extends ExtractionImpl[T] with RequestVal[T] {
  def directive: Directive1[T]
}
private[http] object StandaloneExtractionImpl {
  def apply[T: ClassTag](extractionDirective: Directive1[T]): RequestVal[T] =
    new StandaloneExtractionImpl[T] {
      def directive: Directive1[T] = extractionDirective
    }
}

/**
 * INTERNAL API
 */
private[http] abstract class ExtractingStandaloneExtractionImpl[T: ClassTag] extends StandaloneExtractionImpl[T] {
  def directive: Directive1[T] = Directives.extract(extract).flatMap(Directives.onSuccess(_))

  def extract(ctx: RequestContext): Future[T]
}
