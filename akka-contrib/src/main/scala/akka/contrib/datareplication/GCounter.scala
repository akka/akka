/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object GCounter {
  val empty: GCounter = new GCounter
  def apply(): GCounter = empty
  /**
   * Java API
   */
  def create(): GCounter = empty

  def unapply(value: Any): Option[Long] = value match {
    case c: GCounter ⇒ Some(c.value)
    case _           ⇒ None
  }
}

/**
 * Implements a 'Growing Counter' CRDT, also called a 'G-Counter'.
 *
 * A G-Counter is a increment-only counter (inspired by vector clocks) in
 * which only increment and merge are possible. Incrementing the counter
 * adds 1 to the count for the current node. Divergent histories are
 * resolved by taking the maximum count for each node (like a vector
 * clock merge). The value of the counter is the sum of all node counts.
 */
case class GCounter(
  private[akka] val state: Map[UniqueAddress, Long] = Map.empty)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = GCounter

  def value: Long = state.values.sum

  /**
   * Increment the counter with the delta specified.
   * The delta must be zero or positive.
   */
  def :+(delta: Long)(implicit node: Cluster): GCounter = increment(node, delta)

  /**
   * Increment the counter with the delta specified.
   * The delta must be zero or positive.
   */
  def increment(node: Cluster, delta: Long = 1): GCounter =
    increment(node.selfUniqueAddress, delta)

  /**
   * INTERNAL API
   */
  private[akka] def increment(key: UniqueAddress): GCounter = increment(key, 1)

  /**
   * INTERNAL API
   */
  private[akka] def increment(key: UniqueAddress, delta: Long): GCounter = {
    require(delta >= 0, "Can't decrement a GCounter")
    if (delta == 0) this
    else state.get(key) match {
      case Some(v) ⇒
        val tot = v + delta
        require(tot >= 0, "Number overflow")
        copy(state = state + (key -> tot))
      case None ⇒ copy(state = state + (key -> delta))
    }
  }

  override def merge(that: GCounter): GCounter = {
    var merged = that.state
    for ((key, thisValue) ← state) {
      val thatValue = merged.getOrElse(key, 0L)
      if (thisValue > thatValue)
        merged = merged.updated(key, thisValue)
    }
    GCounter(merged)
  }

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    state.contains(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): GCounter =
    state.get(removedNode) match {
      case Some(value) ⇒ copy(state = state - removedNode).increment(collapseInto, value)
      case None        ⇒ this
    }

  override def pruningCleanup(removedNode: UniqueAddress): GCounter = copy(state = state - removedNode)
}

