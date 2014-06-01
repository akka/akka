/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object PNCounter {
  val empty: PNCounter = new PNCounter(GCounter.empty, GCounter.empty)
  def apply(): PNCounter = empty

  def unapply(value: Any): Option[Long] = value match {
    case c: PNCounter ⇒ Some(c.value)
    case _            ⇒ None
  }
}

/**
 * Implements a 'Increment/Decrement Counter' CRDT, also called a 'PN-Counter'.
 *
 * PN-Counters allow the counter to be incremented by tracking the
 * increments (P) separate from the decrements (N). Both P and N are represented
 * as two internal [[GCounter]]s. Merge is handled by merging the internal P and N
 * counters. The value of the counter is the value of the P counter minus
 * the value of the N counter.
 */
case class PNCounter(
  private[akka] val increments: GCounter, private[akka] val decrements: GCounter)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = PNCounter

  def value: Long = increments.value - decrements.value

  /**
   * Increment the counter with the delta specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def :+(delta: Long)(implicit node: Cluster): PNCounter = increment(node, delta)

  /**
   * Increment the counter with the delta specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(node: Cluster, delta: Long = 1): PNCounter =
    change(node.selfUniqueAddress, delta)

  /**
   * Decrement the counter with the delta specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def :-(delta: Long)(implicit node: Cluster): PNCounter = decrement(node, delta)

  /**
   * Decrement the counter with the delta specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def decrement(node: Cluster, delta: Long = 1): PNCounter =
    change(node.selfUniqueAddress, -delta)

  private[akka] def increment(key: UniqueAddress, delta: Long): PNCounter = change(key, delta)
  private[akka] def increment(key: UniqueAddress): PNCounter = increment(key, 1)
  private[akka] def decrement(key: UniqueAddress, delta: Long): PNCounter = change(key, -math.abs(delta))
  private[akka] def decrement(key: UniqueAddress): PNCounter = decrement(key, 1)

  private[akka] def change(key: UniqueAddress, delta: Long): PNCounter =
    if (delta > 0) copy(increments = increments.increment(key, delta))
    else if (delta < 0) copy(decrements = decrements.increment(key, -delta))
    else this

  override def merge(that: PNCounter): PNCounter =
    copy(increments = that.increments.merge(this.increments),
      decrements = that.decrements.merge(this.decrements))

  override def hasDataFrom(node: UniqueAddress): Boolean =
    increments.hasDataFrom(node) || decrements.hasDataFrom(node)

  override def prune(from: UniqueAddress, to: UniqueAddress): PNCounter =
    copy(increments = increments.prune(from, to),
      decrements = decrements.prune(from, to))

  override def clear(from: UniqueAddress): PNCounter =
    copy(increments = increments.clear(from),
      decrements = decrements.clear(from))
}

