/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.VectorClock
import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object ORSet {
  val empty: ORSet = new ORSet
  def apply(): ORSet = empty

  def unapply(value: Any): Option[Set[Any]] = value match {
    case s: ORSet ⇒ Some(s.value)
    case _        ⇒ None
  }
}

/**
 * Implements a 'Observed Remove Set' CRDT, also called a 'OR-Set'.
 *
 * It is not implemented as in the paper. This is more space inefficient
 * and don't accumulate garbage for removed elements. The is inspired by the
 * description of ORSet in Riak (https://github.com/basho/riak/issues/354).
 *
 * Each element has an active flag and a vector clock. When an element is added
 * the active flag is set to `true` and the vector clock is increased for the
 * node performing the update. When an element is removed from the set the
 * active flag is set to `false` and the vector clock is increased. This is the
 * observed remove (i.e. the node removed all the updates for that element that
 * it has observed, only.) Any concurrent add will result in a vector clock that
 * is not strictly dominated by the remove clock for the element, and on merge,
 * the elements active flag will flip back to `true`, i.e. concurrent add wins
 * over remove.
 */
case class ORSet(
  private[akka] val elements: Map[Any, (Boolean, VectorClock)] = Map.empty)
  extends ReplicatedData with RemovedNodePruning {

  type T = ORSet

  /**
   * Scala API
   */
  def value: Set[Any] = elements.collect { case (elem, (active, _)) if active ⇒ elem }(collection.breakOut)

  /**
   * Java API
   */
  def getValue(): java.util.Set[Any] = {
    import scala.collection.JavaConverters._
    value.asJava
  }

  def contains(a: Any): Boolean = elements.get(a) match {
    case Some((active, _)) ⇒ active
    case None              ⇒ false
  }

  /**
   * Adds an element to the set
   */
  def :+(element: Any)(implicit node: Cluster): ORSet = add(node, element)

  /**
   * Adds an element to the set
   */
  def add(node: Cluster, element: Any): ORSet = add(node.selfUniqueAddress, element)

  /**
   * INTERNAL API
   */
  private[akka] def add(node: UniqueAddress, element: Any): ORSet =
    updated(node, element, active = true)

  private def updated(node: UniqueAddress, element: Any, active: Boolean): ORSet = {
    val newVclock = elements.get(element) match {
      case Some((_, vclock)) ⇒ vclock :+ vclockNode(node)
      case None              ⇒ new VectorClock :+ vclockNode(node)
    }
    copy(elements = elements.updated(element, (active, newVclock)))
  }

  private def vclockNode(node: UniqueAddress): String =
    node.address + "-" + node.uid

  /**
   * Removes an element from the set.
   */
  def :-(element: Any)(implicit node: Cluster): ORSet = remove(node, element)

  /**
   * Removes an element from the set.
   */
  def remove(node: Cluster, element: Any): ORSet = remove(node.selfUniqueAddress, element)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, element: Any): ORSet =
    updated(node, element, active = false)

  override def merge(that: ORSet): ORSet = {
    var merged = that.elements
    for ((elem, thisValue @ (thisActive, thisVclock)) ← elements) {
      merged.get(elem) match {
        case Some((thatActive, thatVclock)) ⇒
          thatVclock.compareTo(thisVclock) match {
            case VectorClock.Same | VectorClock.After ⇒
            case VectorClock.Before ⇒
              merged = merged.updated(elem, thisValue)
            case _ ⇒ // conflicting version
              merged = merged.updated(elem, (thisActive || thatActive, thatVclock.merge(thisVclock)))
          }
        case None ⇒ merged = merged.updated(elem, thisValue)
      }
    }
    copy(merged)
  }

  override def hasDataFrom(node: UniqueAddress): Boolean = {
    val vNode = vclockNode(node)
    elements.exists {
      case (_, (_, vclock)) ⇒ vclock.hasDataFrom(vNode)
    }
  }

  override def prune(from: UniqueAddress, to: UniqueAddress): ORSet = {
    val vFromNode = vclockNode(from)
    val vToNode = vclockNode(to)
    val updated = elements.foldLeft(elements) {
      case (acc, value @ (elem, (active, vclock))) ⇒
        if (vclock.hasDataFrom(vFromNode)) acc.updated(elem, (active, vclock.prune(vFromNode, vToNode)))
        else acc
    }
    copy(updated)
  }

  override def clear(from: UniqueAddress): ORSet = {
    val vFromNode = vclockNode(from)
    val updated = elements.foldLeft(elements) {
      case (acc, value @ (elem, (active, vclock))) ⇒
        if (vclock.hasDataFrom(vFromNode)) acc.updated(elem, (active, vclock.clear(vFromNode)))
        else acc
    }
    copy(updated)
  }
}

