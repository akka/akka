/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.VectorClock
import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.cluster.UniqueAddress
import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

// TODO this class can be optimized, but I wanted to start with correct functionality and comparability with riak_dt_orswot

object ORSet {
  val empty: ORSet = new ORSet
  def apply(): ORSet = empty

  def unapply(value: Any): Option[Set[Any]] = value match {
    case s: ORSet ⇒ Some(s.value)
    case _        ⇒ None
  }

  /**
   * INTERNAL API
   */
  private[akka]type Dot = VectorClock

  /**
   * INTERNAL API
   * Subtract the `vclock` from the `dot`.
   * What this means is that any (node, version) pair in
   * `dot` that is <= an entry in `vclock` is removed from `dot`.
   * Example [{a, 3}, {b, 2}, {d, 14}, {g, 22}] -
   *         [{a, 4}, {b, 1}, {c, 1}, {d, 14}, {e, 5}, {f, 2}] =
   *         [{b, 2}, {g, 22}]
   */
  private[akka] def subtractDots(dot: Dot, vclock: VectorClock): Dot = {

    @tailrec def dropDots(remaining: List[(VectorClock.Node, Long)], acc: List[(VectorClock.Node, Long)]): List[(VectorClock.Node, Long)] =
      remaining match {
        case Nil ⇒ acc
        case (d @ (node, v1)) :: rest ⇒
          vclock.versions.get(node) match {
            case Some(v2) if v2 >= v1 ⇒
              // dot is dominated by clock, drop it
              dropDots(rest, acc)
            case _ ⇒
              dropDots(rest, d :: acc)
          }
      }

    val newDots = dropDots(dot.versions.toList, Nil)
    new VectorClock(versions = TreeMap.empty[VectorClock.Node, Long] ++ newDots)
  }

  /**
   * INTERNAL API
   * @see [[ORSet#merge]]
   */
  private[akka] def mergeCommonKeys(commonKeys: Set[Any], lhs: ORSet, rhs: ORSet): Map[Any, ORSet.Dot] = {
    commonKeys.foldLeft(Map.empty[Any, ORSet.Dot]) {
      case (acc, k) ⇒
        val lhsDots = lhs.elements(k).versions
        val rhsDots = rhs.elements(k).versions
        val commonDots = lhsDots.filter {
          case (thisDotNode, v) ⇒ rhsDots.get(thisDotNode).exists(_ == v)
        }
        val commonDotsKeys = commonDots.keys
        val lhsUniqueDots = lhsDots -- commonDotsKeys
        val rhsUniqueDots = rhsDots -- commonDotsKeys
        val lhsKeep = ORSet.subtractDots(new VectorClock(lhsUniqueDots), rhs.vclock)
        val rhsKeep = ORSet.subtractDots(new VectorClock(rhsUniqueDots), lhs.vclock)
        val merged = lhsKeep.merge(rhsKeep).merge(new VectorClock(versions = commonDots))
        // Perfectly possible that an item in both sets should be dropped
        if (merged.versions.isEmpty) acc
        else acc.updated(k, merged)
    }
  }

  /**
   * INTERNAL API
   * @see [[ORSet#merge]]
   */
  private[akka] def mergeDisjointKeys(keys: Set[Any], elements: Map[Any, ORSet.Dot], vclock: VectorClock,
                                      accumulator: Map[Any, ORSet.Dot]): Map[Any, ORSet.Dot] = {
    keys.foldLeft(accumulator) {
      case (acc, k) ⇒
        val dots = elements(k)
        if (vclock > dots || vclock == dots)
          acc
        else {
          // Optimise the set of stored dots to include only those unseen
          val newDots = subtractDots(dots, vclock)
          acc.updated(k, newDots)
        }
    }
  }
}

/**
 * Implements a 'Observed Remove Set' CRDT, also called a 'OR-Set'.
 * Elements can be added and removed any number of times. Concurrent add wins
 * over remove.
 *
 * It is not implemented as in the paper. This is more space inefficient
 * and don't accumulate garbage for removed elements. It is inspired by the
 * <a href="https://github.com/basho/riak_dt/blob/develop/src/riak_dt_orswot.erl">
 * riak_dt_orswot</a>.
 *
 * The ORSet has a version vector that is incremented when an element is added to
 * the set. The `node -> count` pair for that increment is stored against the
 * element as its "birth dot". Every time the element is re-added to the set,
 * its "birth dot" is updated to that of the `node -> count` version vector entry
 * resulting from the add. When an element is removed, we simply drop it, no tombstones.
 *
 * When an element exists in replica A and not replica B, is it because A added
 * it and B has not yet seen that, or that B removed it and A has not yet seen that?
 * In this implementation we compare the `dot` of the present element to the clock
 * in the Set it is absent from. If the element dot is not "seen" by the Set clock,
 * that means the other set has yet to see this add, and the item is in the merged
 * Set. If the Set clock dominates the dot, that means the other Set has removed this
 * element already, and the item is not in the merged Set.
 */
case class ORSet(
  private[akka] val elements: Map[Any, ORSet.Dot] = Map.empty,
  private[akka] val vclock: VectorClock = new VectorClock)
  extends ReplicatedData with RemovedNodePruning {

  type T = ORSet

  /**
   * Scala API
   */
  def value: Set[Any] = elements.keySet

  /**
   * Java API
   */
  def getValue(): java.util.Set[Any] = {
    import scala.collection.JavaConverters._
    value.asJava
  }

  def contains(a: Any): Boolean = elements.contains(a)

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
  private[akka] def add(node: UniqueAddress, element: Any): ORSet = {
    val vNode = vclockNode(node)
    val newVclock = vclock :+ vNode
    val d = new VectorClock(versions = TreeMap(vNode -> newVclock.versions(vNode)))
    val newDot = elements.get(element) match {
      case Some(existing) ⇒ d.merge(existing)
      case None           ⇒ d
    }
    ORSet(elements = elements.updated(element, newDot), vclock = newVclock)
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
    copy(elements = elements - element)

  /**
   * When element is in this Set but not in that Set:
   * Compare the "birth dot" of the present element to the clock in the Set it is absent from.
   * If the element dot is not "seen" by other Set clock, that means the other set has yet to
   * see this add, and the element is to be in the merged Set.
   * If the other Set clock dominates the dot, that means the other Set has removed
   * the element already, and the element is not to be in the merged Set.
   *
   * When element in both this Set and in that Set:
   * Some dots may still need to be shed. If this Set has dots that the other Set does not have,
   * and the other Set clock dominates those dots, then we need to drop those dots.
   * Keep only common dots, and dots that are not dominated by the other sides clock
   */
  override def merge(that: ORSet): ORSet = {
    val thisKeys = elements.keySet
    val thatKeys = that.elements.keySet
    val commonKeys = thisKeys.intersect(thatKeys)
    val thisUniqueKeys = thisKeys -- commonKeys
    val thatUniqueKeys = thatKeys -- commonKeys

    val entries00 = ORSet.mergeCommonKeys(commonKeys, this, that)
    val entries0 = ORSet.mergeDisjointKeys(thisUniqueKeys, this.elements, that.vclock, entries00)
    val entries = ORSet.mergeDisjointKeys(thatUniqueKeys, that.elements, this.vclock, entries0)
    val mergedVclock = this.vclock.merge(that.vclock)

    ORSet(entries, mergedVclock)
  }

  override def hasDataFrom(node: UniqueAddress): Boolean =
    vclock.hasDataFrom(vclockNode(node))

  override def prune(from: UniqueAddress, to: UniqueAddress): ORSet = {
    val vFromNode = vclockNode(from)
    val vToNode = vclockNode(to)
    val pruned = elements.foldLeft(Map.empty[Any, ORSet.Dot]) {
      case (acc, (elem, dot)) ⇒
        if (dot.hasDataFrom(vFromNode)) acc.updated(elem, dot.prune(vFromNode, vToNode))
        else acc
    }
    if (pruned.isEmpty)
      copy(vclock = vclock.prune(vFromNode, vToNode))
    else {
      // re-add elements that were pruned, to bump dots to right vclock
      val newSet = ORSet(elements = elements ++ pruned, vclock = vclock.prune(vFromNode, vToNode))
      pruned.keys.foldLeft(newSet) {
        case (s, elem) ⇒ s.add(to, elem)
      }
    }

  }

  override def clear(from: UniqueAddress): ORSet = {
    val vFromNode = vclockNode(from)
    val updated = elements.foldLeft(elements) {
      case (acc, (elem, dot)) ⇒
        if (dot.hasDataFrom(vFromNode)) acc.updated(elem, dot.clear(vFromNode))
        else acc
    }
    ORSet(updated, vclock.clear(vFromNode))
  }
}

