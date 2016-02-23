/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object LWWMap {
  private val _empty: LWWMap[Any] = new LWWMap(ORMap.empty)
  def empty[A]: LWWMap[A] = _empty.asInstanceOf[LWWMap[A]]
  def apply(): LWWMap[Any] = _empty
  /**
   * Java API
   */
  def create[A](): LWWMap[A] = empty

  /**
   * Extract the [[LWWMap#entries]].
   */
  def unapply[A](m: LWWMap[A]): Option[Map[String, A]] = Some(m.entries)
}

/**
 * Specialized [[ORMap]] with [[LWWRegister]] values.
 *
 * `LWWRegister` relies on synchronized clocks and should only be used when the choice of
 * value is not important for concurrent updates occurring within the clock skew.
 *
 * Instead of using timestamps based on `System.currentTimeMillis()` time it is possible to
 * use a timestamp value based on something else, for example an increasing version number
 * from a database record that is used for optimistic concurrency control.
 *
 * For first-write-wins semantics you can use the [[LWWRegister#reverseClock]] instead of the
 * [[LWWRegister#defaultClock]]
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class LWWMap[A] private[akka] (
  private[akka] val underlying: ORMap[LWWRegister[A]])
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {
  import LWWRegister.{ Clock, defaultClock }

  type T = LWWMap[A]

  /**
   * Scala API: All entries of the map.
   */
  def entries: Map[String, A] = underlying.entries.map { case (k, r) ⇒ k -> r.value }

  /**
   * Java API: All entries of the map.
   */
  def getEntries(): java.util.Map[String, A] = {
    import scala.collection.JavaConverters._
    entries.asJava
  }

  def get(key: String): Option[A] = underlying.get(key).map(_.value)

  def contains(key: String): Boolean = underlying.contains(key)

  def isEmpty: Boolean = underlying.isEmpty

  def size: Int = underlying.size

  /**
   * Adds an entry to the map
   */
  def +(entry: (String, A))(implicit node: Cluster): LWWMap[A] = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map
   */
  def put(node: Cluster, key: String, value: A): LWWMap[A] =
    put(node, key, value, defaultClock[A])

  /**
   * Adds an entry to the map.
   *
   * You can provide your `clock` implementation instead of using timestamps based
   * on `System.currentTimeMillis()` time. The timestamp can for example be an
   * increasing version number from a database record that is used for optimistic
   * concurrency control.
   */
  def put(node: Cluster, key: String, value: A, clock: Clock[A]): LWWMap[A] =
    put(node.selfUniqueAddress, key, value, clock)

  /**
   * Adds an entry to the map.
   *
   * You can provide your `clock` implementation instead of using timestamps based
   * on `System.currentTimeMillis()` time. The timestamp can for example be an
   * increasing version number from a database record that is used for optimistic
   * concurrency control.
   */
  def put(key: String, value: A)(implicit node: Cluster, clock: Clock[A] = defaultClock[A]): LWWMap[A] =
    put(node, key, value, clock)

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: String, value: A, clock: Clock[A]): LWWMap[A] = {
    val newRegister = underlying.get(key) match {
      case Some(r) ⇒ r.withValue(node, value, clock)
      case None    ⇒ LWWRegister(node, value, clock)
    }
    new LWWMap(underlying.put(node, key, newRegister))
  }

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def -(key: String)(implicit node: Cluster): LWWMap[A] = remove(node, key)

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def remove(node: Cluster, key: String): LWWMap[A] =
    remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: String): LWWMap[A] =
    new LWWMap(underlying.remove(node, key))

  override def merge(that: LWWMap[A]): LWWMap[A] =
    new LWWMap(underlying.merge(that.underlying))

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    underlying.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): LWWMap[A] =
    new LWWMap(underlying.prune(removedNode, collapseInto))

  override def pruningCleanup(removedNode: UniqueAddress): LWWMap[A] =
    new LWWMap(underlying.pruningCleanup(removedNode))

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"LWW$entries" //e.g. LWWMap(a -> 1, b -> 2)

  override def equals(o: Any): Boolean = o match {
    case other: LWWMap[_] ⇒ underlying == other.underlying
    case _                ⇒ false
  }

  override def hashCode: Int = underlying.hashCode
}

object LWWMapKey {
  def create[A](id: String): Key[LWWMap[A]] = LWWMapKey(id)
}

@SerialVersionUID(1L)
final case class LWWMapKey[A](_id: String) extends Key[LWWMap[A]](_id) with ReplicatedDataSerialization
