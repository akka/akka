/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import java.math.BigInteger

object PNCounterMap {
  val empty: PNCounterMap = new PNCounterMap(ORMap.empty)
  def apply(): PNCounterMap = empty
  /**
   * Java API
   */
  def create(): PNCounterMap = empty

  /**
   * Extract the [[PNCounterMap#entries]].
   */
  def unapply(m: PNCounterMap): Option[Map[String, BigInt]] = Some(m.entries)
}

/**
 * Map of named counters. Specialized [[ORMap]] with [[PNCounter]] values.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class PNCounterMap private[akka] (
  private[akka] val underlying: ORMap[PNCounter])
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = PNCounterMap

  /** Scala API */
  def entries: Map[String, BigInt] = underlying.entries.map { case (k, c) ⇒ k → c.value }

  /** Java API */
  def getEntries: java.util.Map[String, BigInteger] = {
    import scala.collection.JavaConverters._
    underlying.entries.map { case (k, c) ⇒ k → c.value.bigInteger }.asJava
  }

  /**
   *  Scala API: The count for a key
   */
  def get(key: String): Option[BigInt] = underlying.get(key).map(_.value)

  /**
   * Java API: The count for a key, or `null` if it doesn't exist
   */
  def getValue(key: String): BigInteger = underlying.get(key).map(_.value.bigInteger).orNull

  def contains(key: String): Boolean = underlying.contains(key)

  def isEmpty: Boolean = underlying.isEmpty

  def size: Int = underlying.size

  /**
   * Increment the counter with the delta specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(key: String, delta: Long = 1)(implicit node: Cluster): PNCounterMap =
    increment(node, key, delta)

  /**
   * Increment the counter with the delta specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(node: Cluster, key: String, delta: Long): PNCounterMap =
    increment(node.selfUniqueAddress, key, delta)

  /**
   * INTERNAL API
   */
  private[akka] def increment(node: UniqueAddress, key: String, delta: Long): PNCounterMap =
    new PNCounterMap(underlying.updated(node, key, PNCounter())(_.increment(node, delta)))

  /**
   * Decrement the counter with the delta specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def decrement(key: String, delta: Long = 1)(implicit node: Cluster): PNCounterMap =
    decrement(node, key, delta)

  /**
   * Decrement the counter with the delta specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def decrement(node: Cluster, key: String, delta: Long): PNCounterMap =
    decrement(node.selfUniqueAddress, key, delta)

  /**
   * INTERNAL API
   */
  private[akka] def decrement(node: UniqueAddress, key: String, delta: Long): PNCounterMap = {
    new PNCounterMap(underlying.updated(node, key, PNCounter())(_.decrement(node, delta)))
  }

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def -(key: String)(implicit node: Cluster): PNCounterMap = remove(node, key)

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def remove(node: Cluster, key: String): PNCounterMap =
    remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: String): PNCounterMap =
    new PNCounterMap(underlying.remove(node, key))

  override def merge(that: PNCounterMap): PNCounterMap =
    new PNCounterMap(underlying.merge(that.underlying))

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    underlying.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): PNCounterMap =
    new PNCounterMap(underlying.prune(removedNode, collapseInto))

  override def pruningCleanup(removedNode: UniqueAddress): PNCounterMap =
    new PNCounterMap(underlying.pruningCleanup(removedNode))

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"PNCounter$entries"

  override def equals(o: Any): Boolean = o match {
    case other: PNCounterMap ⇒ underlying == other.underlying
    case _                   ⇒ false
  }

  override def hashCode: Int = underlying.hashCode
}

object PNCounterMapKey {
  def create[A](id: String): Key[PNCounterMap] = PNCounterMapKey(id)
}

@SerialVersionUID(1L)
final case class PNCounterMapKey(_id: String) extends Key[PNCounterMap](_id) with ReplicatedDataSerialization
