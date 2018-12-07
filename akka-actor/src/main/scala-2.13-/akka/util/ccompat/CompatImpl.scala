/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable.Builder

/**
 * INTERNAL API
 *
 * Based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.11_2.12/scala/collection/compat/CompatImpl.scala
 */
private object CompatImpl {
  def simpleCBF[A, C](f: ⇒ Builder[A, C]): CanBuildFrom[Any, A, C] = new CanBuildFrom[Any, A, C] {
    def apply(from: Any): Builder[A, C] = apply()
    def apply(): Builder[A, C] = f
  }
}
