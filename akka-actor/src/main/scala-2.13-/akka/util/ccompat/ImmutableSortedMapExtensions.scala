/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.{ immutable ⇒ i }

class ImmutableSortedMapExtensions(private val fact: i.SortedMap.type) extends AnyVal {
  def from[K: Ordering, V](source: TraversableOnce[(K, V)]): i.SortedMap[K, V] =
    build(i.SortedMap.newBuilder[K, V], source)
}
