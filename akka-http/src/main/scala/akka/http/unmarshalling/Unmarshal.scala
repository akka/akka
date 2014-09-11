/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import akka.http.util.Deferrable

object Unmarshal {
  def apply[T](value: T): Unmarshal[T] = new Unmarshal(value)
}

class Unmarshal[A](val value: A) {
  /**
   * Unmarshals the value to the given Type using the in-scope Unmarshaller.
   */
  def to[B](implicit um: Unmarshaller[A, B]): Deferrable[B] = um(value)
}
