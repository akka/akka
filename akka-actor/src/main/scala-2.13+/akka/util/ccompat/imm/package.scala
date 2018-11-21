/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.immutable

package object imm {
  type MapLike[K, +V, +This <: immutable.Map[K, V]] = immutable.Map[K, V]
}
