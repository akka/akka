/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import scala.collection.breakOut
import akka.cluster.Cluster
import akka.cluster.UniqueAddress

object ORMap {
  val empty: ORMap = new ORMap
  def apply(): ORMap = empty

  def unapply(value: Any): Option[Map[String, ReplicatedData]] = value match {
    case r: ORMap ⇒ Some(r.entries)
    case _        ⇒ None
  }
}

/**
 * Implements a 'Observed Remove Map' CRDT, also called a 'OR-Map'.
 *
 * It has similar semantics as an [[ORSet]], but in case
 * concurrent updates the values are merged, and must therefore be [[ReplicatedData]]
 * themselves.
 */
case class ORMap(
  private[akka] val keys: ORSet = ORSet(),
  private[akka] val values: Map[String, ReplicatedData] = Map.empty)
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = ORMap

  /**
   * Scala API
   */
  def entries: Map[String, ReplicatedData] = values

  /**
   * Java API
   */
  def getEntries(): java.util.Map[String, ReplicatedData] = {
    import scala.collection.JavaConverters._
    entries.asJava
  }

  def get(key: String): Option[ReplicatedData] = values.get(key)

  /**
   * Adds an entry to the map
   */
  def :+(entry: (String, ReplicatedData))(implicit node: Cluster): ORMap = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map
   */
  def put(node: Cluster, key: String, value: ReplicatedData): ORMap = put(node.selfUniqueAddress, key, value)

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: String, value: ReplicatedData): ORMap =
    ORMap(keys.add(node, key), values.updated(key, value))

  /**
   * Removes an entry from the map.
   */
  def :-(key: String)(implicit node: Cluster): ORMap = remove(node, key)

  /**
   * Removes an entry from the map.
   */
  def remove(node: Cluster, key: String): ORMap = remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: String): ORMap =
    ORMap(keys.remove(node, key), values - key)

  override def merge(that: ORMap): ORMap = {
    val mergedKeys = keys.merge(that.keys)
    var mergedValues = Map.empty[String, ReplicatedData]
    mergedKeys.elements.keysIterator foreach {
      case key: String ⇒
        (this.values.get(key), that.values.get(key)) match {
          case (Some(thisValue), Some(thatValue)) ⇒
            if (thisValue.getClass != thatValue.getClass) {
              val errMsg = s"Wrong type for merging [$key] in [${getClass.getName}], existing type " +
                s"[${thisValue.getClass.getName}], got [${thatValue.getClass.getName}]"
              throw new IllegalArgumentException(errMsg)
            }
            val mergedValue = thisValue.merge(thatValue.asInstanceOf[thisValue.T])
            mergedValues = mergedValues.updated(key, mergedValue)
          case (Some(thisValue), None) ⇒
            mergedValues = mergedValues.updated(key, thisValue)
          case (None, Some(thatValue)) ⇒
            mergedValues = mergedValues.updated(key, thatValue)
          case (None, None) ⇒ throw new IllegalStateException(s"missing value for $key")
        }
    }

    ORMap(mergedKeys, mergedValues)
  }

  override def hasDataFrom(node: UniqueAddress): Boolean = {
    keys.hasDataFrom(node) || values.exists {
      case (_, data: RemovedNodePruning) ⇒ data.hasDataFrom(node)
      case _                             ⇒ false
    }
  }

  override def prune(from: UniqueAddress, to: UniqueAddress): ORMap = {
    val prunedKeys = keys.prune(from, to)
    val prunedValues = values.foldLeft(values) {
      case (acc, (key, data: RemovedNodePruning)) if data.hasDataFrom(from) ⇒
        acc.updated(key, data.prune(from, to))
      case (acc, _) ⇒ acc
    }
    ORMap(prunedKeys, prunedValues)
  }

  override def clear(from: UniqueAddress): ORMap = {
    val clearedKeys = keys.clear(from)
    val clearedValues = values.foldLeft(values) {
      case (acc, (key, data: RemovedNodePruning)) if data.hasDataFrom(from) ⇒
        acc.updated(key, data.clear(from))
      case (acc, _) ⇒ acc
    }
    ORMap(clearedKeys, clearedValues)
  }
}

