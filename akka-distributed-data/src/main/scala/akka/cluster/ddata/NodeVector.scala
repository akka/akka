/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

import akka.cluster.UniqueAddress

/**
 * An abstract node vector, where each node has its own value that it and it only is responsible for.
 *
 * The values of the vector must have a stable merge function, implemented by [[mergeValues()]], which is used to
 * merge different values for the same node when encountered.
 */
@SerialVersionUID(1L)
abstract class NodeVector[V](private val state: Map[UniqueAddress, V])
    extends DeltaReplicatedData
    with ReplicatedDelta
    with RemovedNodePruning
    with FastMerge {

  type T <: NodeVector[V]
  final type D = T

  /**
   * Create a new vector with the given state and delta.
   *
   * This is needed by operations such as [[merge()]] to create the new version of this CRDT.
   */
  protected def newVector(state: Map[UniqueAddress, V], delta: Option[T]): T

  /**
   * Merge the given values for a node.
   */
  protected def mergeValues(thisValue: V, thatValue: V): V

  /**
   * Collapse the given value into the given node.
   */
  protected def collapseInto(key: UniqueAddress, value: V): T

  // Is not final because overrides can implement it without allocating.
  override def zero: T = newVector(Map.empty, None)

  protected final def update(key: UniqueAddress, nextValue: V): T = {
    val newDelta = delta match {
      case None    => newVector(Map(key -> nextValue), None)
      case Some(d) => newVector(d.state + (key -> nextValue), None)
    }
    assignAncestor(newVector(state + (key -> nextValue), Some(newDelta)))
  }

  override final def merge(that: T): T =
    if ((this eq that) || that.isAncestorOf(this.asInstanceOf[that.T])) clearAncestor().asInstanceOf[T]
    else if (this.isAncestorOf(that)) that.clearAncestor()
    else {
      var merged = that.state
      for ((key, thisValue) <- state) {
        merged.get(key) match {
          case Some(thatValue) =>
            val newValue = mergeValues(thisValue, thatValue)
            if (newValue != thatValue)
              merged = merged.updated(key, newValue)
          case None =>
            merged = merged.updated(key, thisValue)
        }
      }
      clearAncestor()
      newVector(merged, None)
    }

  override final def mergeDelta(thatDelta: T): T = merge(thatDelta)

  override final def resetDelta: T =
    if (delta.isEmpty) this.asInstanceOf[T]
    else assignAncestor(newVector(state, None))

  override final def modifiedByNodes: Set[UniqueAddress] = state.keySet

  override final def needPruningFrom(removedNode: UniqueAddress): Boolean =
    state.contains(removedNode)

  override final def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): T =
    state.get(removedNode) match {
      case Some(value) => newVector(state - removedNode, None).collapseInto(collapseInto, value).asInstanceOf[T]
      case None        => this.asInstanceOf[T]
    }

  override final def pruningCleanup(removedNode: UniqueAddress): T =
    newVector(state - removedNode, None)

}
