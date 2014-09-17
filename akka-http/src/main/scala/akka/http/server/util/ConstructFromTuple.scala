/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.util

trait ConstructFromTuple[T, R] {
  def apply(t: T): R
}
object ConstructFromTuple extends ConstructFromTupleInstances
