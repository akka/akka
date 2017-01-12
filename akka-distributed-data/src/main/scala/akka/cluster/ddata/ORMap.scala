/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.util.HashCode

object ORMap {
  private val _empty: ORMap[Any, ReplicatedData] = new ORMap(ORSet.empty, Map.empty)
  def empty[A, B <: ReplicatedData]: ORMap[A, B] = _empty.asInstanceOf[ORMap[A, B]]
  def apply(): ORMap[Any, ReplicatedData] = _empty
  /**
   * Java API
   */
  def create[A, B <: ReplicatedData](): ORMap[A, B] = empty[A, B]

  /**
   * Extract the [[ORMap#entries]].
   */
  def unapply[A, B <: ReplicatedData](m: ORMap[A, B]): Option[Map[A, B]] = Some(m.entries)

}

/**
 * Implements a 'Observed Remove Map' CRDT, also called a 'OR-Map'.
 *
 * It has similar semantics as an [[ORSet]], but in case of concurrent updates
 * the values are merged, and must therefore be [[ReplicatedData]] types themselves.
 *
 * This class is immutable, i.e. "modifying" methods return a new instance.
 */
@SerialVersionUID(1L)
final class ORMap[A, B <: ReplicatedData] private[akka] (
  private[akka] val keys:   ORSet[A],
  private[akka] val values: Map[A, B])
  extends ReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  type T = ORMap[A, B]

  /**
   * Scala API: All entries of the map.
   */
  def entries: Map[A, B] = values

  /**
   * Java API: All entries of the map.
   */
  def getEntries(): java.util.Map[A, B] = {
    import scala.collection.JavaConverters._
    entries.asJava
  }

  def get(key: A): Option[B] = values.get(key)

  /**
   * Scala API: Get the value associated with the key if there is one,
   * else return the given default.
   */
  def getOrElse(key: A, default: ⇒ B): B = values.getOrElse(key, default)

  def contains(key: A): Boolean = values.contains(key)

  def isEmpty: Boolean = values.isEmpty

  def size: Int = values.size

  /**
   * Adds an entry to the map
   * @see [[#put]]
   */
  def +(entry: (A, B))(implicit node: Cluster): ORMap[A, B] = {
    val (key, value) = entry
    put(node, key, value)
  }

  /**
   * Adds an entry to the map.
   * Note that the new `value` will be merged with existing values
   * on other nodes and the outcome depends on what `ReplicatedData`
   * type that is used.
   *
   * Consider using [[#updated]] instead of `put` if you want modify
   * existing entry.
   *
   * `IllegalArgumentException` is thrown if you try to replace an existing `ORSet`
   * value, because important history can be lost when replacing the `ORSet` and
   * undesired effects of merging will occur. Use [[ORMultiMap]] or [[#updated]] instead.
   */
  def put(node: Cluster, key: A, value: B): ORMap[A, B] = put(node.selfUniqueAddress, key, value)

  /**
   * INTERNAL API
   */
  private[akka] def put(node: UniqueAddress, key: A, value: B): ORMap[A, B] =
    if (value.isInstanceOf[ORSet[_]] && values.contains(key))
      throw new IllegalArgumentException(
        "`ORMap.put` must not be used to replace an existing `ORSet` " +
          "value, because important history can be lost when replacing the `ORSet` and " +
          "undesired effects of merging will occur. Use `ORMultiMap` or `ORMap.updated` instead.")
    else
      new ORMap(keys.add(node, key), values.updated(key, value))

  /**
   * Scala API: Replace a value by applying the `modify` function on the existing value.
   *
   * If there is no current value for the `key` the `initial` value will be
   * passed to the `modify` function.
   */
  def updated(node: Cluster, key: A, initial: B)(modify: B ⇒ B): ORMap[A, B] =
    updated(node.selfUniqueAddress, key, initial)(modify)

  /**
   * Java API: Replace a value by applying the `modify` function on the existing value.
   *
   * If there is no current value for the `key` the `initial` value will be
   * passed to the `modify` function.
   */
  def updated(node: Cluster, key: A, initial: B, modify: java.util.function.Function[B, B]): ORMap[A, B] =
    updated(node, key, initial)(value ⇒ modify.apply(value))

  /**
   * INTERNAL API
   */
  private[akka] def updated(node: UniqueAddress, key: A, initial: B)(modify: B ⇒ B): ORMap[A, B] = {
    val newValue = values.get(key) match {
      case Some(old) ⇒ modify(old)
      case _         ⇒ modify(initial)
    }
    new ORMap(keys.add(node, key), values.updated(key, newValue))
  }

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def -(key: A)(implicit node: Cluster): ORMap[A, B] = remove(node, key)

  /**
   * Removes an entry from the map.
   * Note that if there is a conflicting update on another node the entry will
   * not be removed after merge.
   */
  def remove(node: Cluster, key: A): ORMap[A, B] = remove(node.selfUniqueAddress, key)

  /**
   * INTERNAL API
   */
  private[akka] def remove(node: UniqueAddress, key: A): ORMap[A, B] = {
    new ORMap(keys.remove(node, key), values - key)
  }

  override def merge(that: ORMap[A, B]): ORMap[A, B] = {
    val mergedKeys = keys.merge(that.keys)
    var mergedValues = Map.empty[A, B]
    mergedKeys.elementsMap.keysIterator.foreach { key ⇒
      (this.values.get(key), that.values.get(key)) match {
        case (Some(thisValue), Some(thatValue)) ⇒
          if (thisValue.getClass != thatValue.getClass) {
            val errMsg = s"Wrong type for merging [$key] in [${getClass.getName}], existing type " +
              s"[${thisValue.getClass.getName}], got [${thatValue.getClass.getName}]"
            throw new IllegalArgumentException(errMsg)
          }
          // TODO can we get rid of these (safe) casts?
          val mergedValue = thisValue.merge(thatValue.asInstanceOf[thisValue.T]).asInstanceOf[B]
          mergedValues = mergedValues.updated(key, mergedValue)
        case (Some(thisValue), None) ⇒
          mergedValues = mergedValues.updated(key, thisValue)
        case (None, Some(thatValue)) ⇒
          mergedValues = mergedValues.updated(key, thatValue)
        case (None, None) ⇒ throw new IllegalStateException(s"missing value for $key")
      }
    }

    new ORMap(mergedKeys, mergedValues)
  }

  override def needPruningFrom(removedNode: UniqueAddress): Boolean = {
    keys.needPruningFrom(removedNode) || values.exists {
      case (_, data: RemovedNodePruning) ⇒ data.needPruningFrom(removedNode)
      case _                             ⇒ false
    }
  }

  override def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): ORMap[A, B] = {
    val prunedKeys = keys.prune(removedNode, collapseInto)
    val prunedValues = values.foldLeft(values) {
      case (acc, (key, data: RemovedNodePruning)) if data.needPruningFrom(removedNode) ⇒
        acc.updated(key, data.prune(removedNode, collapseInto).asInstanceOf[B])
      case (acc, _) ⇒ acc
    }
    new ORMap(prunedKeys, prunedValues)
  }

  override def pruningCleanup(removedNode: UniqueAddress): ORMap[A, B] = {
    val pruningCleanupedKeys = keys.pruningCleanup(removedNode)
    val pruningCleanupedValues = values.foldLeft(values) {
      case (acc, (key, data: RemovedNodePruning)) if data.needPruningFrom(removedNode) ⇒
        acc.updated(key, data.pruningCleanup(removedNode).asInstanceOf[B])
      case (acc, _) ⇒ acc
    }
    new ORMap(pruningCleanupedKeys, pruningCleanupedValues)
  }

  // this class cannot be a `case class` because we need different `unapply`

  override def toString: String = s"OR$entries"

  override def equals(o: Any): Boolean = o match {
    case other: ORMap[_, _] ⇒ keys == other.keys && values == other.values
    case _                  ⇒ false
  }

  override def hashCode: Int = {
    var result = HashCode.SEED
    result = HashCode.hash(result, keys)
    result = HashCode.hash(result, values)
    result
  }

}

object ORMapKey {
  def create[A, B <: ReplicatedData](id: String): Key[ORMap[A, B]] = ORMapKey(id)
}

@SerialVersionUID(1L)
final case class ORMapKey[A, B <: ReplicatedData](_id: String) extends Key[ORMap[A, B]](_id) with ReplicatedDataSerialization
