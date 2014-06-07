/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object LWWRegister {

  /**
   * INTERNAL API
   */
  private[akka] def apply(node: UniqueAddress, initialValue: Any): LWWRegister =
    new LWWRegister(node, initialValue, defaultClock(), defaultClock)

  def apply(node: Cluster, initialValue: Any): LWWRegister =
    apply(node.selfUniqueAddress, initialValue)

  /**
   * Java API
   */
  def create(node: Cluster, initialValue: Any): LWWRegister =
    apply(node.selfUniqueAddress, initialValue)

  def unapply(value: Any): Option[Any] = value match {
    case r: LWWRegister ⇒ Some(r.value)
    case _              ⇒ None
  }

  /**
   * INTERNAL API
   */
  private[akka] abstract class Clock extends (() ⇒ Long) with Serializable
  private[akka] val defaultClock = new Clock {
    def apply() = System.currentTimeMillis()
  }
}

/**
 * Implements a 'Last Writer Wins Register' CRDT, also called a 'LWW-Register'.
 *
 * Merge takes the the register with highest timestamp. Note that this
 * relies on synchronized clocks. `LWWRegister` should only be used when the choice of
 * value is not important for concurrent updates occurring within the clock skew.
 *
 * Merge takes the register updated by the node with lowest address (`UniqueAddress` is ordered)
 * if the timestamps are exactly the same.
 */
case class LWWRegister(
  private[akka] val node: UniqueAddress,
  private[akka] val state: Any,
  private[akka] val timestamp: Long,
  private[akka] val clock: LWWRegister.Clock)
  extends ReplicatedData with ReplicatedDataSerialization {

  type T = LWWRegister

  /**
   * Scala API
   */
  def value: Any = state

  /**
   * Java API
   */
  def getValue(): AnyRef = state.asInstanceOf[AnyRef]

  def withValue(node: Cluster, value: Any): LWWRegister =
    withValue(node.selfUniqueAddress, value)

  def updatedBy: UniqueAddress = node

  /**
   * INTERNAL API
   */
  private[akka] def withValue(node: UniqueAddress, value: Any): LWWRegister =
    copy(node = node, state = value, timestamp = math.max(clock(), timestamp + 1))

  override def merge(that: LWWRegister): LWWRegister =
    if (that.timestamp > this.timestamp) that
    else if (that.timestamp < this.timestamp) this
    else if (that.node < this.node) that
    else this
}

