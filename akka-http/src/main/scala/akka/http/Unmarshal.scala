/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.concurrent.Future
import akka.http.unmarshalling.{ Unmarshalling, Unmarshaller }

object Unmarshal {
  def apply[T](value: T): Unmarshal[T] = new Unmarshal(value)
}

class Unmarshal[A](val value: A) {
  /**
   * Unmarshals the value to the given Type using the in-scope Unmarshaller.
   */
  def to[B](implicit um: Unmarshaller[A, B]): Future[Unmarshalling[B]] = um(value)
}
