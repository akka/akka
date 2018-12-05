/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.{ immutable ⇒ i }

/**
 * Based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.11_2.12/scala/collection/compat/PackageShared.scala
 */
class ImmutableTreeMapExtensions(private val fact: i.TreeMap.type) extends AnyVal {
  def from[K: Ordering, V](source: TraversableOnce[(K, V)]): i.TreeMap[K, V] =
    build(i.TreeMap.newBuilder[K, V], source)
}
