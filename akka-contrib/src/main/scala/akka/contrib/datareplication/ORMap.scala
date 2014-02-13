/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.VectorClock
import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object ORMap {
  val empty: ORMap = new ORMap
  def apply(): ORMap = empty

  def unapply(value: Any): Option[Map[Any, ReplicatedData]] = value match {
    case r: ORMap ⇒ Some(r.entries)
    case _        ⇒ None
  }
}

/**
 * Implements a 'Observed Remove Map' CRDT, also called a 'OR-Map'.
 *
 * It has similar semantics and implementation as an [[ORSet]], but in case
 * concurrent updates the values are merged, and must therefore be [[ReplicatedData]]
 * themselves.
 */
case class ORMap(
  private[akka] val elements: Map[Any, (Option[ReplicatedData], VectorClock)] = Map.empty)
  extends ReplicatedData with RemovedNodePruning {

  type T = ORMap

  /**
   * Scala API
   */
  def entries: Map[Any, ReplicatedData] = elements.collect { case (key, (Some(value), _)) ⇒ key -> value }

  /**
   * Java API
   */
  def getEntries(): java.util.Map[Any, ReplicatedData] = {
    import scala.collection.JavaConverters._
    entries.asJava
  }

  def get(key: Any): Option[ReplicatedData] = elements.get(key) match {
    case Some((data, _)) ⇒ data
    case None            ⇒ None
  }

  /**
   * Adds an entry to the map
   */
  def :+(entry: (Any, ReplicatedData))(implicit node: Cluster): ORMap = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map
   */
  def put(node: Cluster, key: Any, value: ReplicatedData): ORMap = put(node.selfUniqueAddress, key, value)

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: Any, value: ReplicatedData): ORMap =
    updated(node, key, Some(value))

  private def updated(node: UniqueAddress, key: Any, value: Option[ReplicatedData]): ORMap = {
    val newVclock = elements.get(key) match {
      case Some((_, vclock)) ⇒ vclock :+ vclockNode(node)
      case None              ⇒ new VectorClock :+ vclockNode(node)
    }
    copy(elements = elements.updated(key, (value, newVclock)))
  }

  private def vclockNode(node: UniqueAddress): String =
    node.address + "-" + node.uid

  /**
   * Removes an entry from the map.
   */
  def :-(key: Any)(implicit node: Cluster): ORMap = remove(node, key)

  /**
   * Removes an entry from the map.
   */
  def remove(node: Cluster, key: Any): ORMap = remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: Any): ORMap = updated(node, key, None)

  override def merge(that: ORMap): ORMap = {
    var merged = that.elements
    for ((key, thisValue @ (thisData, thisVclock)) ← elements) {
      merged.get(key) match {
        case Some((thatData, thatVclock)) ⇒
          thatVclock.compareTo(thisVclock) match {
            case VectorClock.Same | VectorClock.After ⇒
            case VectorClock.Before ⇒
              merged = merged.updated(key, thisValue)
            case _ ⇒ // conflicting version
              val mergedVclock = thatVclock.merge(thisVclock)
              val mergedData = (thisData, thatData) match {
                case (None, None)                                   ⇒ None
                case (v1 @ Some(_), None)                           ⇒ v1
                case (None, v2 @ Some(_))                           ⇒ v2
                case (Some(a), Some(b)) if a.getClass == b.getClass ⇒ Some(a.merge(b.asInstanceOf[a.T]))
                case (Some(a), Some(b)) ⇒
                  val errMsg = s"Wrong type for merging [$key] in [$getClass.getName], existing type " +
                    s"[${a.getClass.getName}], got [${b.getClass.getName}]"
                  throw new IllegalArgumentException(errMsg)
              }
              merged = merged.updated(key, (mergedData, mergedVclock))
          }
        case None ⇒ merged = merged.updated(key, thisValue)
      }
    }
    copy(merged)
  }

  override def hasDataFrom(node: UniqueAddress): Boolean = {
    val vNode = vclockNode(node)
    elements.exists {
      case (_, (Some(data: RemovedNodePruning), vclock)) ⇒
        vclock.hasDataFrom(vNode) || data.hasDataFrom(node)
      case (_, (_, vclock)) ⇒
        vclock.hasDataFrom(vNode)
    }
  }

  override def prune(from: UniqueAddress, to: UniqueAddress): ORMap = {
    val vFromNode = vclockNode(from)
    val vToNode = vclockNode(to)
    val updated = elements.foldLeft(elements) {
      case (acc, (key, (data, vclock))) ⇒
        val prunedData = data match {
          case Some(d: RemovedNodePruning) if d.hasDataFrom(from) ⇒ Some(d.prune(from, to))
          case _ ⇒ data
        }
        val prunedVclock =
          if (vclock.hasDataFrom(vFromNode)) {
            val v = vclock.prune(vFromNode, vToNode)
            if (prunedData eq data) v
            else v :+ vToNode
          } else vclock
        if ((prunedData eq data) && (prunedVclock eq vclock)) acc
        else acc.updated(key, (prunedData, prunedVclock))
    }
    copy(updated)
  }

  override def clear(from: UniqueAddress): ORMap = {
    val vFromNode = vclockNode(from)
    val updated = elements.foldLeft(elements) {
      case (acc, (key, (data, vclock))) ⇒
        val prunedVclock =
          if (vclock.hasDataFrom(vFromNode)) vclock.clear(vFromNode)
          else vclock
        val prunedData = data match {
          case Some(d: RemovedNodePruning) if d.hasDataFrom(from) ⇒ Some(d.clear(from))
          case _ ⇒ data
        }
        if ((prunedData eq data) && (prunedVclock eq vclock)) acc
        else acc.updated(key, (prunedData, prunedVclock))
    }
    copy(updated)
  }
}

