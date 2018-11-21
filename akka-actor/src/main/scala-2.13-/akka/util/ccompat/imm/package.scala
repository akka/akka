/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.immutable

package object imm {
  type MapLike[K, +V, +This <: MapLike[K, V, This] with immutable.Map[K, V]] = immutable.MapLike[K, V, This]
}
