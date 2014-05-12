/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object LWWMap {
  val empty: LWWMap = new LWWMap
  def apply(): LWWMap = empty

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
  private val underlying: ORMap = ORMap.empty)
  extends ReplicatedData with RemovedNodePruning {

  type T = LWWMap

  def entries: Map[String, Any] = underlying.entries.map { case (k: String, r: LWWRegister) ⇒ k -> r.value }

  def get(key: Any): Option[Any] = underlying.get(key) match {
    case Some(r: LWWRegister) ⇒ Some(r.value)
    case _                    ⇒ None
  }

  /**
   * Adds an entry to the map
   */
  def :+(entry: (Any, Any))(implicit node: Cluster): LWWMap = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map
   */
  def put(node: Cluster, key: Any, value: Any): LWWMap = {
    val newRegister = underlying.get(key) match {
      case Some(r: LWWRegister) ⇒ r.value = value
      case _                    ⇒ LWWRegister(value)
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

  override def hasDataFrom(node: UniqueAddress): Boolean =
    underlying.hasDataFrom(node)

  override def prune(from: UniqueAddress, to: UniqueAddress): LWWMap =
    copy(underlying.prune(from, to))

  override def clear(from: UniqueAddress): LWWMap =
    copy(underlying.clear(from))
}

