/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.util.HashCode
import java.math.BigInteger

object PNCounter {
  val empty: PNCounter = new PNCounter(GCounter.empty, GCounter.empty)
  def apply(): PNCounter = empty
  /**
   * Java API
   */
  def create(): PNCounter = empty

  /**
   * Extract the [[GCounter#value]].
   */
  def unapply(c: PNCounter): Option[BigInt] = Some(c.value)
}

/**
 * Implements a 'Increment/Decrement Counter' CRDT, also called a 'PN-Counter'.
 *
 * It is described in the paper
 * <a href="http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf">A comprehensive study of Convergent and Commutative Replicated Data Types</a>.
 *
 * PN-Counters allow the counter to be incremented by tracking the
 * increments (P) separate from the decrements (N). Both P and N are represented
 * as two internal [[GCounter]]s. Merge is handled by merging the internal P and N
 * counters. The value of the counter is the value of the P counter minus
 * the value of the N counter.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class PNCounter private[akka] (
  private[akka] val increments: GCounter, private[akka] val decrements: GCounter)
  extends DeltaReplicatedData with ReplicatedDelta
  with ReplicatedDataSerialization with RemovedNodePruning {

  type T = PNCounter
  type D = PNCounter

  /**
   * Scala API: Current total value of the counter.
   */
  def value: BigInt = increments.value - decrements.value

  /**
   * Java API: Current total value of the counter.
   */
  def getValue: BigInteger = value.bigInteger

  /**
   * Increment the counter with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def +(n: Long)(implicit node: Cluster): PNCounter = increment(node, n)

  /**
   * Increment the counter with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(node: Cluster, n: Long = 1): PNCounter =
    increment(node.selfUniqueAddress, n)

  /**
   * Decrement the counter with the delta `n` specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def -(n: Long)(implicit node: Cluster): PNCounter = decrement(node, n)

  /**
   * Decrement the counter with the delta `n` specified.
   * If the delta `n` is negative then it will increment instead of decrement.
   */
  def decrement(node: Cluster, n: Long = 1): PNCounter =
    decrement(node.selfUniqueAddress, n)

  private[akka] def increment(key: UniqueAddress, n: Long): PNCounter = change(key, n)
  private[akka] def increment(key: UniqueAddress): PNCounter = increment(key, 1)
  private[akka] def decrement(key: UniqueAddress, n: Long): PNCounter = change(key, -n)
  private[akka] def decrement(key: UniqueAddress): PNCounter = decrement(key, 1)

  private[akka] def change(key: UniqueAddress, n: Long): PNCounter =
    if (n > 0) copy(increments = increments.increment(key, n))
    else if (n < 0) copy(decrements = decrements.increment(key, -n))
    else this

  override def merge(that: PNCounter): PNCounter =
    copy(
      increments = that.increments.merge(this.increments),
      decrements = that.decrements.merge(this.decrements))

  override def delta: Option[PNCounter] = {
    if (increments.delta.isEmpty && decrements.delta.isEmpty)
      None
    else {
      val incrementsDelta = increments.delta match {
        case Some(d) ⇒ d
        case None    ⇒ GCounter.empty
      }
      val decrementsDelta = decrements.delta match {
        case Some(d) ⇒ d
        case None    ⇒ GCounter.empty
      }
      Some(new PNCounter(incrementsDelta, decrementsDelta))
    }
  }

  override def mergeDelta(thatDelta: PNCounter): PNCounter = merge(thatDelta)

  override def zero: PNCounter = PNCounter.empty

  override def resetDelta: PNCounter =
    if (increments.delta.isEmpty && decrements.delta.isEmpty) this
    else new PNCounter(increments.resetDelta, decrements.resetDelta)

  override def modifiedByNodes: Set[UniqueAddress] =
    increments.modifiedByNodes union decrements.modifiedByNodes

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    increments.needPruningFrom(removedNode) || decrements.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): PNCounter =
    copy(
      increments = increments.prune(removedNode, collapseInto),
      decrements = decrements.prune(removedNode, collapseInto))

  override def pruningCleanup(removedNode: UniqueAddress): PNCounter =
    copy(
      increments = increments.pruningCleanup(removedNode),
      decrements = decrements.pruningCleanup(removedNode))

  private def copy(increments: GCounter = this.increments, decrements: GCounter = this.decrements): PNCounter =
    new PNCounter(increments, decrements)

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"PNCounter($value)"

  override def equals(o: Any): Boolean = o match {
    case other: PNCounter ⇒
      increments == other.increments && decrements == other.decrements
    case _ ⇒ false
  }

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, increments)
    result = HashCode.hash(result, decrements)
    result
  }

}

object PNCounterKey {
  def create[A](id: String): Key[PNCounter] = PNCounterKey(id)
}

@SerialVersionUID(1L)
final case class PNCounterKey(_id: String) extends Key[PNCounter](_id) with ReplicatedDataSerialization
