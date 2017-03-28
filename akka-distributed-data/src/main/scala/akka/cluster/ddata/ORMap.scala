/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.Cluster
import akka.cluster.UniqueAddress
import akka.util.HashCode
import akka.annotation.InternalApi
import akka.cluster.ddata.ORMap.{ AtomicDeltaOp, ZeroTag }

import scala.collection.immutable

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

  sealed trait DeltaOp extends ReplicatedDelta with RequiresCausalDeliveryOfDeltas with ReplicatedDataSerialization {
    type T = DeltaOp
    override def zero: DeltaReplicatedData
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def emptyWithPNCounterMapTag[A, B <: ReplicatedData]: ORMap[A, B] = new ORMap(ORSet.empty, Map.empty, zeroTag = PNCounterMapTag)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def emptyWithORMultiMapTag[A, B <: ReplicatedData]: ORMap[A, B] = new ORMap(ORSet.empty, Map.empty, zeroTag = ORMultiMapTag)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def emptyWithORMultiMapWithValueDeltasTag[A, B <: ReplicatedData]: ORMap[A, B] = new ORMap(ORSet.empty, Map.empty, zeroTag = ORMultiMapWithValueDeltasTag)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def emptyWithLWWMapTag[A, B <: ReplicatedData]: ORMap[A, B] = new ORMap(ORSet.empty, Map.empty, zeroTag = LWWMapTag)

  /**
   * INTERNAL API
   * Tags for ORMap.DeltaOp's, so that when the Replicator needs to re-create full value from delta,
   * the right map type will be used
   */
  @InternalApi private[akka] trait ZeroTag {
    def zero: DeltaReplicatedData
    def value: Int
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case object VanillaORMapTag extends ZeroTag {
    override def zero: DeltaReplicatedData = ORMap.empty
    override final val value: Int = 0
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case object PNCounterMapTag extends ZeroTag {
    override def zero: DeltaReplicatedData = PNCounterMap.empty
    override final val value: Int = 1
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case object ORMultiMapTag extends ZeroTag {
    override def zero: DeltaReplicatedData = ORMultiMap.empty
    override final val value: Int = 2
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case object ORMultiMapWithValueDeltasTag extends ZeroTag {
    override def zero: DeltaReplicatedData = ORMultiMap.emptyWithValueDeltas
    override final val value: Int = 3
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] case object LWWMapTag extends ZeroTag {
    override def zero: DeltaReplicatedData = LWWMap.empty
    override final val value: Int = 4
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] sealed abstract class AtomicDeltaOp[A, B <: ReplicatedData] extends DeltaOp {
    def underlying: ORSet.DeltaOp
    def zeroTag: ZeroTag
    override def zero: DeltaReplicatedData = zeroTag.zero

    override def merge(that: DeltaOp): DeltaOp = that match {
      case other: AtomicDeltaOp[A, B] ⇒ DeltaGroup(Vector(this, other))
      case DeltaGroup(ops)            ⇒ DeltaGroup(this +: ops)
    }
  }

  // PutDeltaOp contains ORSet delta and full value
  /** INTERNAL API */
  @InternalApi private[akka] final case class PutDeltaOp[A, B <: ReplicatedData](underlying: ORSet.DeltaOp, value: (A, B), zeroTag: ZeroTag = VanillaORMapTag) extends AtomicDeltaOp[A, B] {
  }

  // UpdateDeltaOp contains ORSet delta and either delta of value (in case where underlying type supports deltas) or full value
  /** INTERNAL API */
  @InternalApi private[akka] final case class UpdateDeltaOp[A, X <: ReplicatedDelta](underlying: ORSet.DeltaOp, values: Map[A, X], zeroTag: ZeroTag = VanillaORMapTag) extends AtomicDeltaOp[A, X] {
  }

  // RemoveDeltaOp does not contain any value at all - the propagated 'value' map is empty
  /** INTERNAL API */
  @InternalApi private[akka] final case class RemoveDeltaOp[A, B <: ReplicatedData](underlying: ORSet.DeltaOp, zeroTag: ZeroTag = VanillaORMapTag) extends AtomicDeltaOp[A, B] {
  }

  // RemoveKeyDeltaOp contains a single value - to provide the recipient with the removed key for value map
  /** INTERNAL API */
  @InternalApi private[akka] final case class RemoveKeyDeltaOp[A, B <: ReplicatedData](underlying: ORSet.DeltaOp, removedKey: A, zeroTag: ZeroTag = VanillaORMapTag) extends AtomicDeltaOp[A, B] {
  }

  // DeltaGroup is effectively a causally ordered list of individual deltas
  /** INTERNAL API */
  @InternalApi private[akka] final case class DeltaGroup[A, B <: ReplicatedData](ops: immutable.IndexedSeq[DeltaOp]) extends DeltaOp {
    override def merge(that: DeltaOp): DeltaOp = that match {
      case DeltaGroup(thatOps)       ⇒ DeltaGroup(ops ++ thatOps)
      case that: AtomicDeltaOp[A, B] ⇒ DeltaGroup(ops :+ that)
    }

    override def zero: DeltaReplicatedData = ops.headOption.fold(ORMap.empty[A, B].asInstanceOf[DeltaReplicatedData])(_.zero)
  }
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
  private[akka] val keys:    ORSet[A],
  private[akka] val values:  Map[A, B],
  private[akka] val zeroTag: ZeroTag               = ORMap.VanillaORMapTag,
  override val delta:        Option[ORMap.DeltaOp] = None)
  extends DeltaReplicatedData with ReplicatedDataSerialization with RemovedNodePruning {

  import ORMap.{ PutDeltaOp, UpdateDeltaOp, RemoveDeltaOp, RemoveKeyDeltaOp }

  type T = ORMap[A, B]
  type D = ORMap.DeltaOp

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
  @InternalApi private[akka] def put(node: UniqueAddress, key: A, value: B): ORMap[A, B] =
    if (value.isInstanceOf[ORSet[_]] && values.contains(key))
      throw new IllegalArgumentException(
        "`ORMap.put` must not be used to replace an existing `ORSet` " +
          "value, because important history can be lost when replacing the `ORSet` and " +
          "undesired effects of merging will occur. Use `ORMultiMap` or `ORMap.updated` instead.")
    else {
      val newKeys = keys.resetDelta.add(node, key)
      val putDeltaOp = PutDeltaOp(newKeys.delta.get, key → value, zeroTag)
      // put forcibly damages history, so we consciously propagate full value that will overwrite previous value
      new ORMap(newKeys, values.updated(key, value), zeroTag, Some(newDelta(putDeltaOp)))
    }

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
  @InternalApi private[akka] def updated(node: UniqueAddress, key: A, initial: B, valueDeltas: Boolean = false)(modify: B ⇒ B): ORMap[A, B] = {
    val (oldValue, hasOldValue) = values.get(key) match {
      case Some(old) ⇒ (old, true)
      case _         ⇒ (initial, false)
    }
    // Optimization: for some types - like GSet, GCounter, PNCounter and ORSet  - that are delta based
    // we can emit (and later merge) their deltas instead of full updates.
    // However to avoid necessity of tombstones, the derived map type needs to support this
    // with clearing the value (e.g. removing all elements if value is a set)
    // before removing the key - like e.g. ORMultiMap.emptyWithValueDeltas does
    val newKeys = keys.resetDelta.add(node, key)
    oldValue match {
      case _: DeltaReplicatedData if valueDeltas ⇒
        val newValue = modify(oldValue.asInstanceOf[DeltaReplicatedData].resetDelta.asInstanceOf[B])
        val newValueDelta = newValue.asInstanceOf[DeltaReplicatedData].delta
        val deltaOp = newValueDelta match {
          case Some(d) if hasOldValue ⇒ UpdateDeltaOp(newKeys.delta.get, Map(key → d), zeroTag)
          case _                      ⇒ PutDeltaOp(newKeys.delta.get, key → newValue, zeroTag)
        }
        new ORMap(newKeys, values.updated(key, newValue), zeroTag, Some(newDelta(deltaOp)))
      case _ ⇒
        val newValue = modify(oldValue)
        val deltaOp = PutDeltaOp(newKeys.delta.get, key → newValue, zeroTag)
        new ORMap(newKeys, values.updated(key, newValue), zeroTag, Some(newDelta(deltaOp)))
    }
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
  @InternalApi private[akka] def remove(node: UniqueAddress, key: A): ORMap[A, B] = {
    // for removals the delta values map emitted will be empty
    val newKeys = keys.resetDelta.remove(node, key)
    val removeDeltaOp = RemoveDeltaOp(newKeys.delta.get, zeroTag)
    new ORMap(newKeys, values - key, zeroTag, Some(newDelta(removeDeltaOp)))
  }

  /**
   * INTERNAL API
   * This function is only to be used by derived maps that avoid remove anomalies
   * by keeping the vvector (in form of key -> value pair) for deleted keys
   */
  @InternalApi private[akka] def removeKey(node: UniqueAddress, key: A): ORMap[A, B] = {
    val newKeys = keys.resetDelta.remove(node, key)
    val removeKeyDeltaOp = RemoveKeyDeltaOp(newKeys.delta.get, key, zeroTag)
    new ORMap(newKeys, values, zeroTag, Some(newDelta(removeKeyDeltaOp)))
  }

  private def dryMerge(that: ORMap[A, B], mergedKeys: ORSet[A], valueKeysIterator: Iterator[A]): ORMap[A, B] = {
    var mergedValues = Map.empty[A, B]
    valueKeysIterator.foreach { key ⇒
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
    new ORMap(mergedKeys, mergedValues, zeroTag = zeroTag)
  }

  override def merge(that: ORMap[A, B]): ORMap[A, B] = {
    val mergedKeys = keys.merge(that.keys)
    dryMerge(that, mergedKeys, mergedKeys.elementsMap.keysIterator)
  }

  /**
   * INTERNAL API
   * This function is only to be used by derived maps that avoid remove anomalies
   * by keeping the vvector (in form of key -> value pair) for deleted keys
   */
  @InternalApi private[akka] def mergeRetainingDeletedValues(that: ORMap[A, B]): ORMap[A, B] = {
    val mergedKeys = keys.merge(that.keys)
    dryMerge(that, mergedKeys, (this.values.keySet ++ that.values.keySet).iterator)
  }

  override def resetDelta: ORMap[A, B] =
    if (delta.isEmpty) this
    else new ORMap[A, B](keys.resetDelta, values, zeroTag = zeroTag)

  override def mergeDelta(thatDelta: ORMap.DeltaOp): ORMap[A, B] = {
    // helper function to simplify folds below
    def foldValues(values: List[(A, ReplicatedData)], initial: B) =
      values.foldLeft(initial) {
        case (acc: DeltaReplicatedData, (_, value: ReplicatedDelta)) ⇒
          acc.mergeDelta(value.asInstanceOf[acc.D]).asInstanceOf[B]
        case (acc, (_, value)) ⇒
          acc.merge(value.asInstanceOf[acc.T]).asInstanceOf[B]
      }

    val mergedKeys: ORSet[A] = thatDelta match {
      case d: AtomicDeltaOp[A, B] ⇒ keys.mergeDelta(d.underlying)
      case ORMap.DeltaGroup(ops) ⇒
        ops.foldLeft(keys)((acc, op) ⇒ acc.mergeDelta(op.asInstanceOf[AtomicDeltaOp[A, B]].underlying))
    }

    var mergedValues = Map.empty[A, B]
    var tombstonedVals = Set.empty[A]
    var thatValueDeltas: Map[A, List[(A, ReplicatedData)]] = Map.empty

    val processDelta: PartialFunction[ORMap.DeltaOp, Unit] = {
      case putOp: PutDeltaOp[A, B] ⇒
        val key = putOp.value._1
        thatValueDeltas += (key → (putOp.value :: Nil)) // put is destructive!
      case removeOp: RemoveDeltaOp[A, B] ⇒
        val removedKey = removeOp.underlying match {
          // if op is RemoveDeltaOp then it must have exactly one element in the elements
          case op: ORSet.RemoveDeltaOp[_] ⇒ op.underlying.elements.head.asInstanceOf[A]
        }
        thatValueDeltas -= removedKey
      // please note that if RemoveDelta is not preceded by update clearing the value
      // anomalies may result
      case removeKeyOp: RemoveKeyDeltaOp[A, B] ⇒
        tombstonedVals = tombstonedVals + removeKeyOp.removedKey
        thatValueDeltas -= removeKeyOp.removedKey
      case updateOp: UpdateDeltaOp[A, _] ⇒
        val key = updateOp.values.head._1
        val value = (key, updateOp.values.head._2)
        if (thatValueDeltas.contains(key))
          thatValueDeltas = thatValueDeltas + (key → (thatValueDeltas(key) :+ value))
        else
          thatValueDeltas += (key → (value :: Nil))
    }

    val processNestedDelta: PartialFunction[ORMap.DeltaOp, Unit] = {
      case ORMap.DeltaGroup(ops) ⇒
        ops.foreach {
          processDelta.orElse {
            case ORMap.DeltaGroup(args) ⇒
              throw new IllegalStateException("Cannot nest DeltaGroups")
          }
        }
    }

    (processDelta orElse processNestedDelta)(thatDelta)

    val aggregateValuesForKey: (A ⇒ Unit) = { key ⇒
      (this.values.get(key), thatValueDeltas.get(key)) match {
        case (Some(thisValue), Some(thatValues)) ⇒
          val mergedValue = foldValues(thatValues, thisValue)
          mergedValues = mergedValues.updated(key, mergedValue)
        case (Some(thisValue), None) ⇒
          mergedValues = mergedValues.updated(key, thisValue)
        case (None, Some(thatValues)) ⇒
          val (_, initialValue) = thatValues.head
          val mergedValue = initialValue match {
            case _: ReplicatedDelta ⇒
              foldValues(thatValues, initialValue.asInstanceOf[ReplicatedDelta].zero.asInstanceOf[B])
            case _ ⇒
              foldValues(thatValues.tail, initialValue.asInstanceOf[B])
          }
          mergedValues = mergedValues.updated(key, mergedValue)
        case (None, None) ⇒ throw new IllegalStateException(s"missing value for $key")
      }
    }

    mergedKeys.elementsMap.keysIterator.foreach { aggregateValuesForKey }
    tombstonedVals.foreach { aggregateValuesForKey }

    new ORMap[A, B](mergedKeys, mergedValues, zeroTag = zeroTag)
  }

  private def newDelta(deltaOp: ORMap.DeltaOp) = delta match {
    case Some(d) ⇒
      d.merge(deltaOp)
    case None ⇒
      deltaOp
  }

  override def modifiedByNodes: Set[UniqueAddress] = {
    keys.modifiedByNodes union values.foldLeft(Set.empty[UniqueAddress]) {
      case (acc, (_, data: RemovedNodePruning)) ⇒ acc union data.modifiedByNodes
      case (acc, _)                             ⇒ acc
    }
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
    new ORMap(prunedKeys, prunedValues, zeroTag = zeroTag)
  }

  override def pruningCleanup(removedNode: UniqueAddress): ORMap[A, B] = {
    val pruningCleanupedKeys = keys.pruningCleanup(removedNode)
    val pruningCleanupedValues = values.foldLeft(values) {
      case (acc, (key, data: RemovedNodePruning)) if data.needPruningFrom(removedNode) ⇒
        acc.updated(key, data.pruningCleanup(removedNode).asInstanceOf[B])
      case (acc, _) ⇒ acc
    }
    new ORMap(pruningCleanupedKeys, pruningCleanupedValues, zeroTag = zeroTag)
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
