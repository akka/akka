/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.{ MemberStatus, UniqueAddress }

import scala.collection.JavaConverters._

object Gauge {
  val empty: Gauge = new Gauge
  def apply(): Gauge = empty

  /**
   * Java API
   */
  def create(): Gauge = empty

  private val Zero = BigInt(0)
}

/**
 * Implements a gauge CRDT.
 *
 * A gauge CRDT has a value for each node in the cluster. These values can then be aggregated, with a sum, average,
 * minimum or maximum.
 *
 * The gauge aggregations are calculated on request by supplying the current cluster state, along with the set of
 * member statuses that are allowed to take part in the aggregation.
 */
@SerialVersionUID(1L)
final case class Gauge private[akka] (
    private[akka] val state: Map[UniqueAddress, (BigInt, BigInt)] = Map.empty,
    override val delta: Option[Gauge] = None)
    extends NodeVector[(BigInt, BigInt)](state)
    with ReplicatedDataSerialization {

  type T = Gauge

  import Gauge.Zero

  /**
   * Scala API: The sum of the current gauge values for each allowed node.
   */
  def sum(allowedStatuses: Set[MemberStatus] = Set(MemberStatus.Up))(
      implicit currentClusterState: CurrentClusterState): BigInt = {
    filtered(allowedStatuses, currentClusterState).sum
  }

  /**
   * Java API: The sum of the current gauge values for each allowed node.
   */
  def getSum(
      allowedStatuses: java.util.Collection[MemberStatus],
      currentClusterState: CurrentClusterState): java.math.BigInteger =
    sum(allowedStatuses.asScala.toSet)(currentClusterState).bigInteger

  /**
   * Scala API: The average of the current gauge values for each allowed node.
   */
  def average(allowedStatuses: Set[MemberStatus] = Set(MemberStatus.Up))(
      implicit currentClusterState: CurrentClusterState): Double = {
    val (nodes, total) = filtered(allowedStatuses, currentClusterState).foldLeft((0, 0d)) {
      case ((nodes, total), gauge) => (nodes + 1, total + gauge.toDouble)
    }
    if (nodes > 0) total / nodes
    else 0
  }

  /**
   * Java API: The average of the current gauge values for each allowed node.
   */
  def getAverage(
      allowedStatuses: java.util.Collection[MemberStatus],
      currentClusterState: CurrentClusterState): Double =
    average(allowedStatuses.asScala.toSet)(currentClusterState)

  /**
   * Scala API: The minimum of the current gauge values for each allowed node.
   */
  def min(allowedStatuses: Set[MemberStatus] = Set(MemberStatus.Up))(
      implicit currentClusterState: CurrentClusterState): BigInt = {
    filtered(allowedStatuses, currentClusterState).min
  }

  /**
   * Java API: The minimum of the current gauge values for each allowed node.
   */
  def getMin(
      allowedStatuses: java.util.Collection[MemberStatus],
      currentClusterState: CurrentClusterState): java.math.BigInteger =
    min(allowedStatuses.asScala.toSet)(currentClusterState).bigInteger

  /**
   * Scala API: The maximum of the current gauge values for each allowed node.
   */
  def max(allowedStatuses: Set[MemberStatus] = Set(MemberStatus.Up))(
      implicit currentClusterState: CurrentClusterState): BigInt = {
    filtered(allowedStatuses, currentClusterState).max
  }

  /**
   * Java API: The maximum of the current gauge values for each allowed node.
   */
  def getMax(
      allowedStatuses: java.util.Collection[MemberStatus],
      currentClusterState: CurrentClusterState): java.math.BigInteger =
    max(allowedStatuses.asScala.toSet)(currentClusterState).bigInteger

  private def filtered(allowedStatuses: Set[MemberStatus], currentClusterState: CurrentClusterState) = {
    state
      .filterKeys(key =>
        currentClusterState.members.exists { member =>
          member.uniqueAddress == key && allowedStatuses(member.status)
        })
      .values
      .map { case (increments, decrements) => increments - decrements }
  }

  /**
   * Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def :+(n: Long)(implicit node: SelfUniqueAddress): Gauge = increment(n)

  /**
   * Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def :+(n: BigInt)(implicit node: SelfUniqueAddress): Gauge = increment(n)

  /**
   * Scala API: Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(n: Long)(implicit node: SelfUniqueAddress): Gauge = changeRelative(node.uniqueAddress, n)

  /**
   * Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(n: BigInt)(implicit node: SelfUniqueAddress): Gauge = changeRelative(node.uniqueAddress, n)

  /**
   * Java API: Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(node: SelfUniqueAddress, n: java.math.BigInteger): Gauge = changeRelative(node.uniqueAddress, n)

  /**
   * Java API: Increment this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will decrement instead of increment.
   */
  def increment(node: SelfUniqueAddress, n: Long): Gauge = changeRelative(node.uniqueAddress, n)

  /**
   * Decrement this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def decrement(n: Long)(implicit node: SelfUniqueAddress): Gauge = changeRelative(node.uniqueAddress, -n)

  /**
   * Decrement this nodes value for the gauge with the delta `n` specified.
   * If the delta is negative then it will increment instead of decrement.
   */
  def decrement(n: BigInt)(implicit node: SelfUniqueAddress): Gauge = changeRelative(node.uniqueAddress, -n)

  /**
   * Decrement this nodes value for the gauge with the delta `n` specified.
   * If the delta `n` is negative then it will increment instead of decrement.
   */
  def decrement(node: SelfUniqueAddress, n: Long): Gauge = changeRelative(node.uniqueAddress, -n)

  /**
   * Scala API: Decrement this nodes value for the gauge with the delta `n` specified.
   * If the delta `n` is negative then it will increment instead of decrement.
   */
  def decrement(node: SelfUniqueAddress, n: BigInt): Gauge = changeRelative(node.uniqueAddress, -n)

  /**
   * Java API: Decrement this nodes value for the gauge with the delta `n` specified.
   * If the delta `n` is negative then it will increment instead of decrement.
   */
  def decrement(node: SelfUniqueAddress, n: java.math.BigInteger): Gauge =
    changeRelative(node.uniqueAddress, -BigInt(n))

  /**
   * Scala API: Set this nodes value for the gauge to `n`.
   */
  def set(n: Long)(implicit node: SelfUniqueAddress): Gauge = changeAbsolute(node.uniqueAddress, n)

  /**
   * Scala API: Set this nodes value for the gauge to `n`.
   */
  def set(n: BigInt)(implicit node: SelfUniqueAddress): Gauge = changeAbsolute(node.uniqueAddress, n)

  /**
   * Java API: Set this nodes value for the gauge to `n`.
   */
  def set(node: SelfUniqueAddress, n: java.math.BigInteger): Gauge = changeAbsolute(node.uniqueAddress, n)

  /**
   * Java API: Set this nodes value for the gauge to `n`.
   */
  def set(node: SelfUniqueAddress, n: Long): Gauge = changeAbsolute(node.uniqueAddress, n)

  private def changeRelative(address: UniqueAddress, n: BigInt) = {
    if (n == 0) this
    else {
      val updated = state.get(address) match {
        case Some((i, d)) =>
          if (n > 0) (i + n, d)
          else (i, d - n)
        case None =>
          if (n > 0) (n, Zero)
          else (Zero, -n)
      }
      update(address, updated)
    }
  }

  private def changeAbsolute(address: UniqueAddress, n: BigInt) = {
    state.get(address) match {
      case Some((i, d)) =>
        val change = n - (i - d)
        if (change == 0) this
        else if (change > 0) update(address, (i + change, d))
        else update(address, (i, d - change))
      case None =>
        if (n == 0) this
        else if (n > 0) update(address, (n, Zero))
        else update(address, (Zero, -n))
    }
  }

  override protected def newVector(state: Map[UniqueAddress, (BigInt, BigInt)], delta: Option[Gauge]): Gauge =
    new Gauge(state, delta)

  override protected def mergeValues(thisValue: (BigInt, BigInt), thatValue: (BigInt, BigInt)): (BigInt, BigInt) =
    if (thisValue == thatValue) thatValue
    else
      (
        if (thisValue._1 > thatValue._1) thisValue._1 else thatValue._1,
        if (thisValue._2 > thatValue._2) thisValue._2 else thatValue._2)

  override protected def collapseInto(key: UniqueAddress, value: (BigInt, BigInt)): Gauge = this

  override def zero: Gauge = Gauge.empty

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String =
    s"Gauge(${state.map { case (a, v) => s"${a.address}@${a.longUid} -> ${v._1 - v._2}" }.mkString(",")})"
}

object GaugeKey {
  def create(id: String): Key[Gauge] = GaugeKey(id)
}

@SerialVersionUID(1L)
final case class GaugeKey(_id: String) extends Key[Gauge](_id) with ReplicatedDataSerialization
