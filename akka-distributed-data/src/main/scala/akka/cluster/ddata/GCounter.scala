/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import java.math.BigInteger
import akka.annotation.InternalApi

object GCounter {
  val empty: GCounter = new GCounter
  def apply(): GCounter = empty

  /**
   * Java API
   */
  def create(): GCounter = empty

  /**
   * Extract the [[GCounter#value]].
   */
  def unapply(c: GCounter): Option[BigInt] = Some(c.value)

  private val Zero = BigInt(0)
}

/**
 * Implements a 'Growing Counter' CRDT, also called a 'G-Counter'.
 *
 * It is described in the paper
 * <a href="http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf">A comprehensive study of Convergent and Commutative Replicated Data Types</a>.
 *
 * A G-Counter is a increment-only counter (inspired by vector clocks) in
 * which only increment and merge are possible. Incrementing the counter
 * adds 1 to the count for the current node. Divergent histories are
 * resolved by taking the maximum count for each node (like a vector
 * clock merge). The value of the counter is the sum of all node counts.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class GCounter private[akka] (
    private[akka] val state: Map[UniqueAddress, BigInt] = Map.empty,
    override val delta: Option[GCounter] = None)
    extends NodeVector[BigInt](state)
    with ReplicatedDataSerialization {

  type T = GCounter

  import GCounter.Zero

  /**
   * Scala API: Current total value of the counter.
   */
  def value: BigInt = state.values.foldLeft(Zero) { (acc, v) =>
    acc + v
  }

  /**
   * Java API: Current total value of the counter.
   */
  def getValue: BigInteger = value.bigInteger

  /**
   * Increment the counter with the delta `n` specified.
   * The delta must be zero or positive.
   */
  def :+(n: Long)(implicit node: SelfUniqueAddress): GCounter = increment(node.uniqueAddress, n)

  @deprecated("Use `:+` that takes a `SelfUniqueAddress` parameter instead.", since = "2.5.20")
  def +(n: Long)(implicit node: Cluster): GCounter = increment(node.selfUniqueAddress, n)

  /**
   * Increment the counter with the delta `n` specified.
   * The delta `n` must be zero or positive.
   */
  def increment(node: SelfUniqueAddress, n: Long): GCounter = increment(node.uniqueAddress, n)

  @deprecated("Use `increment` that takes a `SelfUniqueAddress` parameter instead.", since = "2.5.20")
  def increment(node: Cluster, n: Long = 1): GCounter = increment(node.selfUniqueAddress, n)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def increment(key: UniqueAddress): GCounter = increment(key, 1)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def increment(key: UniqueAddress, n: BigInt): GCounter = {
    require(n >= 0, "Can't decrement a GCounter")
    if (n == 0) this
    else {
      val nextValue = state.get(key) match {
        case Some(v) => v + n
        case None    => n
      }
      update(key, nextValue)
    }
  }

  override protected def newVector(state: Map[UniqueAddress, BigInt], delta: Option[GCounter]): GCounter =
    new GCounter(state, delta)

  override protected def mergeValues(thisValue: BigInt, thatValue: BigInt): BigInt =
    if (thisValue > thatValue) thisValue
    else thatValue

  override protected def collapseInto(key: UniqueAddress, value: BigInt): GCounter =
    increment(key, value)

  override def zero: GCounter = GCounter.empty

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"GCounter($value)"

  override def equals(o: Any): Boolean = o match {
    case other: GCounter => state == other.state
    case _               => false
  }

  override def hashCode: Int = state.hashCode

}

object GCounterKey {
  def create(id: String): Key[GCounter] = GCounterKey(id)
}

@SerialVersionUID(1L)
final case class GCounterKey(_id: String) extends Key[GCounter](_id) with ReplicatedDataSerialization
