/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util.ccompat

import scala.language.higherKinds
import scala.language.implicitConversions
import scala.collection.GenTraversable
import scala.collection.generic._
import scala.collection.{ immutable ⇒ i }
import scala.collection.{ mutable ⇒ m }
import scala.{ collection ⇒ c }

/**
 * Based on https://github.com/scala/scala-collection-compat/blob/master/compat/src/main/scala-2.11_2.12/scala/collection/compat/PackageShared.scala
 */
private[ccompat] trait PackageShared {
  import CompatImpl._

  /**
   * A factory that builds a collection of type `C` with elements of type `A`.
   *
   * @tparam A Type of elements (e.g. `Int`, `Boolean`, etc.)
   * @tparam C Type of collection (e.g. `List[Int]`, `TreeMap[Int, String]`, etc.)
   */
  type Factory[-A, +C] <: CanBuildFrom[Nothing, A, C] // Ideally, this would be an opaque type

  implicit class FactoryOps[-A, +C](private val factory: Factory[A, C]) {

    /**
     * @return A collection of type `C` containing the same elements
     *         as the source collection `it`.
     * @param it Source collection
     */
    def fromSpecific(it: TraversableOnce[A]): C = (factory() ++= it).result()

    /**
     * Get a Builder for the collection. For non-strict collection types this will use an intermediate buffer.
     * Building collections with `fromSpecific` is preferred because it can be lazy for lazy collections.
     */
    def newBuilder: m.Builder[A, C] = factory()
  }

  implicit def fromCanBuildFrom[A, C](implicit cbf: CanBuildFrom[Nothing, A, C]): Factory[A, C] =
    cbf.asInstanceOf[Factory[A, C]]

  implicit def genericCompanionToCBF[A, CC[X] <: GenTraversable[X]](
    fact: GenericCompanion[CC]): CanBuildFrom[Any, A, CC[A]] =
    simpleCBF(fact.newBuilder[A])

  implicit def sortedSetCompanionToCBF[A: Ordering, CC[X] <: c.SortedSet[X] with c.SortedSetLike[X, CC[X]]](
    fact: SortedSetFactory[CC]): CanBuildFrom[Any, A, CC[A]] =
    simpleCBF(fact.newBuilder[A])

  private[ccompat] def build[T, CC](builder: m.Builder[T, CC], source: TraversableOnce[T]): CC = {
    builder ++= source
    builder.result()
  }

  implicit def toImmutableSortedMapExtensions(
    fact: i.SortedMap.type): ImmutableSortedMapExtensions =
    new ImmutableSortedMapExtensions(fact)

  implicit def toImmutableTreeMapExtensions(fact: i.TreeMap.type): ImmutableTreeMapExtensions =
    new ImmutableTreeMapExtensions(fact)

  implicit def toSortedExtensionMethods[K, V <: Sorted[K, V]](
    fact: Sorted[K, V]): SortedExtensionMethods[K, V] =
    new SortedExtensionMethods[K, V](fact)

  // This really belongs into scala.collection but there's already a package object
  // in scala-library so we can't add to it
  type IterableOnce[+X] = c.TraversableOnce[X]
  val IterableOnce = c.TraversableOnce
}
