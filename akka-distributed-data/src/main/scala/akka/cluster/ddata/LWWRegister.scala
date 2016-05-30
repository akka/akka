/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.util.HashCode

object LWWRegister {

  trait Clock[A] {
    /**
     * @param currentTimestamp the current `timestamp` value of the `LWWRegister`
     * @param value the register value to set and associate with the returned timestamp
     * @return next timestamp
     */
    def apply(currentTimestamp: Long, value: A): Long
  }

  private val _defaultClock: Clock[Any] = new Clock[Any] {
    override def apply(currentTimestamp: Long, value: Any): Long =
      math.max(System.currentTimeMillis(), currentTimestamp + 1)
  }

  /**
   * The default [[LWWRegister.Clock]] is using max value of `System.currentTimeMillis()`
   * and `currentTimestamp + 1`.
   */
  def defaultClock[A]: Clock[A] = _defaultClock.asInstanceOf[Clock[A]]

  private val _reverseClock = new Clock[Any] {
    override def apply(currentTimestamp: Long, value: Any): Long =
      math.min(-System.currentTimeMillis(), currentTimestamp - 1)
  }

  /**
   * This [[LWWRegister.Clock]] can be used for first-write-wins semantics. It is using min value of
   * `-System.currentTimeMillis()` and `currentTimestamp + 1`, i.e. it is counting backwards.
   */
  def reverseClock[A]: Clock[A] = _reverseClock.asInstanceOf[Clock[A]]

  /**
   * INTERNAL API
   */
  private[akka] def apply[A](node: UniqueAddress, initialValue: A, clock: Clock[A]): LWWRegister[A] =
    new LWWRegister(node, initialValue, clock(0L, initialValue))

  def apply[A](initialValue: A)(implicit node: Cluster, clock: Clock[A] = defaultClock[A]): LWWRegister[A] =
    apply(node.selfUniqueAddress, initialValue, clock)

  /**
   * Java API
   */
  def create[A](node: Cluster, initialValue: A): LWWRegister[A] =
    apply(initialValue)(node)

  /**
   * Java API
   */
  def create[A](node: Cluster, initialValue: A, clock: Clock[A]): LWWRegister[A] =
    apply(initialValue)(node, clock)

  /**
   * Extract the [[LWWRegister#value]].
   */
  def unapply[A](c: LWWRegister[A]): Option[A] = Some(c.value)

}

/**
 * Implements a 'Last Writer Wins Register' CRDT, also called a 'LWW-Register'.
 *
 * It is described in the paper
 * <a href="http://hal.upmc.fr/file/index/docid/555588/filename/techreport.pdf">A comprehensive study of Convergent and Commutative Replicated Data Types</a>.
 *
 * Merge takes the register with highest timestamp. Note that this
 * relies on synchronized clocks. `LWWRegister` should only be used when the choice of
 * value is not important for concurrent updates occurring within the clock skew.
 *
 * Merge takes the register updated by the node with lowest address (`UniqueAddress` is ordered)
 * if the timestamps are exactly the same.
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
final class LWWRegister[A] private[akka] (
  private[akka] val node: UniqueAddress,
  val value:              A,
  val timestamp:          Long)
  extends ReplicatedData with ReplicatedDataSerialization {
  import LWWRegister.{ Clock, defaultClock }

  type T = LWWRegister[A]

  /**
   * Java API
   */
  def getValue(): A = value

  /**
   * Change the value of the register.
   *
   * You can provide your `clock` implementation instead of using timestamps based
   * on `System.currentTimeMillis()` time. The timestamp can for example be an
   * increasing version number from a database record that is used for optimistic
   * concurrency control.
   */
  def withValue(value: A)(implicit node: Cluster, clock: Clock[A] = defaultClock[A]): LWWRegister[A] =
    withValue(node, value)

  /**
   * Change the value of the register.
   */
  def withValue(node: Cluster, value: A): LWWRegister[A] =
    withValue(node, value, defaultClock[A])

  /**
   * Change the value of the register.
   *
   * You can provide your `clock` implementation instead of using timestamps based
   * on `System.currentTimeMillis()` time. The timestamp can for example be an
   * increasing version number from a database record that is used for optimistic
   * concurrency control.
   */
  def withValue(node: Cluster, value: A, clock: Clock[A]): LWWRegister[A] =
    withValue(node.selfUniqueAddress, value, clock)

  /**
   * The current `value` was set by this node.
   */
  def updatedBy: UniqueAddress = node

  /**
   * INTERNAL API
   */
  private[akka] def withValue(node: UniqueAddress, value: A, clock: Clock[A]): LWWRegister[A] =
    new LWWRegister(node, value, clock(timestamp, value))

  override def merge(that: LWWRegister[A]): LWWRegister[A] =
    if (that.timestamp > this.timestamp) that
    else if (that.timestamp < this.timestamp) this
    else if (that.node < this.node) that
    else this

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"LWWRegister($value)"

  override def equals(o: Any): Boolean = o match {
    case other: LWWRegister[_] ⇒
      timestamp == other.timestamp && value == other.value && node == other.node
    case _ ⇒ false
  }

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, timestamp)
    result = HashCode.hash(result, node)
    result = HashCode.hash(result, value)
    result
  }

}

object LWWRegisterKey {
  def create[A](id: String): Key[LWWRegister[A]] = LWWRegisterKey(id)
}

@SerialVersionUID(1L)
final case class LWWRegisterKey[A](_id: String) extends Key[LWWRegister[A]](_id) with ReplicatedDataSerialization
