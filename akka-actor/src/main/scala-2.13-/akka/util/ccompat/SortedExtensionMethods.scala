/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.collection.generic.Sorted

/**
 * Based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.11_2.12/scala/collection/compat/PackageShared.scala
 */
class SortedExtensionMethods[K, T <: Sorted[K, T]](private val fact: Sorted[K, T]) {
  def rangeFrom(from: K): T = fact.from(from)
  def rangeTo(to: K): T = fact.to(to)
  def rangeUntil(until: K): T = fact.until(until)
}
