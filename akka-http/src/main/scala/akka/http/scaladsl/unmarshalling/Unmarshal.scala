/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.unmarshalling

import akka.stream.Materializer

import scala.concurrent.{ ExecutionContext, Future }

object Unmarshal {
  def apply[T](value: T): Unmarshal[T] = new Unmarshal(value)
}

class Unmarshal[A](val value: A) {
  /**
   * Unmarshals the value to the given Type using the in-scope Unmarshaller.
   *
   * Uses the default materializer [[ExecutionContext]] if no implicit execution context is provided.
   * If you expect the marshalling to be heavy, it is suggested to provide a specialized context for those operations.
   */
  def to[B](implicit um: Unmarshaller[A, B], ec: ExecutionContext = null, mat: Materializer): Future[B] = {
    val context: ExecutionContext = if (ec == null) mat.executionContext else ec

    um(value)(context, mat)
  }
}
