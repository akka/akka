/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object LWWMap {
  val empty: LWWMap = new LWWMap
  def apply(): LWWMap = empty
  /**
   * Java API
   */
  def create(): LWWMap = empty

  def unapply(value: Any): Option[Map[String, Any]] = value match {
    case m: LWWMap ⇒ Some(m.entries)
    case _         ⇒ None
  }
}

/**
 * Specialized [[ORMap]] with [[LWWRegister]] values.
 *
 * `LWWRegister` relies on synchronized clocks and should only be used when the choice of
 * value is not important for concurrent updates occurring within the clock skew.
 *
 */
case class LWWMap(
  private[akka] val underlying: ORMap = ORMap.empty)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = LWWMap

  def entries: Map[String, Any] = underlying.entries.map { case (k, r: LWWRegister) ⇒ k -> r.value }

  def get(key: String): Option[Any] = underlying.get(key) match {
    case Some(r: LWWRegister) ⇒ Some(r.value)
    case _                    ⇒ None
  }

  /**
   * Adds an entry to the map
   */
  def :+(entry: (String, Any))(implicit node: Cluster): LWWMap = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map
   */
  def put(node: Cluster, key: String, value: Any): LWWMap =
    put(node.selfUniqueAddress, key, value)

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: String, value: Any): LWWMap = {
    val newRegister = underlying.get(key) match {
      case Some(r: LWWRegister) ⇒ r.withValue(node, value)
      case _                    ⇒ LWWRegister(node, value)
    }
    copy(underlying.put(node, key, newRegister))
  }

  /**
   * Removes an entry from the map.
   */
  def :-(key: String)(implicit node: Cluster): LWWMap = remove(node, key)

  /**
   * Removes an entry from the map.
   */
  def remove(node: Cluster, key: String): LWWMap =
    copy(underlying.remove(node, key))

  override def merge(that: LWWMap): LWWMap =
    copy(underlying.merge(that.underlying))

  override def needPruningFrom(removedNode: UniqueAddress): Boolean =
    underlying.needPruningFrom(removedNode)

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): LWWMap =
    copy(underlying.prune(removedNode, collapseInto))

  override def pruningCleanup(removedNode: UniqueAddress): LWWMap =
    copy(underlying.pruningCleanup(removedNode))
}

