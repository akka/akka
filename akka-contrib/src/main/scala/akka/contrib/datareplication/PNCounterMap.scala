/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object PNCounterMap {
  val empty: PNCounterMap = new PNCounterMap
  def apply(): PNCounterMap = empty

  def unapply(value: Any): Option[Map[String, Long]] = value match {
    case m: PNCounterMap ⇒ Some(m.entries)
    case _               ⇒ None
  }
}

/**
 * Map of named counters. Specialized [[ORMap]] with [[PNCounter]] values.
 */
case class PNCounterMap(
  private[akka] val underlying: ORMap = ORMap.empty)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = PNCounterMap

  def entries: Map[String, Long] = underlying.entries.map { case (k, c: PNCounter) ⇒ k -> c.value }

  def get(key: String): Option[Long] = underlying.get(key) match {
    case Some(c: PNCounter) ⇒ Some(c.value)
    case _                  ⇒ None
  }

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
  private[akka] def increment(node: UniqueAddress, key: String, delta: Long): PNCounterMap = {
    val counter = underlying.get(key) match {
      case Some(c: PNCounter) ⇒ c
      case _                  ⇒ PNCounter()
    }
    copy(underlying.put(node, key, counter.increment(node, delta)))
  }

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
    val counter = underlying.get(key) match {
      case Some(c: PNCounter) ⇒ c
      case _                  ⇒ PNCounter()
    }
    copy(underlying.put(node, key, counter.decrement(node, delta)))
  }

  /**
   * Removes an entry from the map.
   */
  def :-(key: String)(implicit node: Cluster): PNCounterMap = remove(node, key)

  /**
   * Removes an entry from the map.
   */
  def remove(node: Cluster, key: String): PNCounterMap =
    copy(underlying.remove(node, key))

  override def merge(that: PNCounterMap): PNCounterMap =
    copy(underlying.merge(that.underlying))

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    underlying.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): PNCounterMap =
    copy(underlying.prune(removedNode, collapseInto))

  override def pruningCleanup(removedNode: UniqueAddress): PNCounterMap =
    copy(underlying.pruningCleanup(removedNode))
}

