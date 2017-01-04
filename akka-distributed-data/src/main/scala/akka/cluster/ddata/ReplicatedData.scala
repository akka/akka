/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import akka.cluster.UniqueAddress

/**
 * Interface for implementing a state based convergent
 * replicated data type (CvRDT).
 *
 * ReplicatedData types must be serializable with an Akka
 * Serializer. It is highly recommended to implement a serializer with
 * Protobuf or similar. The built in data types are marked with
 * [[ReplicatedDataSerialization]] and serialized with
 * [[akka.cluster.ddata.protobuf.ReplicatedDataSerializer]].
 *
 * Serialization of the data types are used in remote messages and also
 * for creating message digests (SHA-1) to detect changes. Therefore it is
 * important that the serialization produce the same bytes for the same content.
 * For example sets and maps should be sorted deterministically in the serialization.
 *
 * ReplicatedData types should be immutable, i.e. "modifying" methods should return
 * a new instance.
 *
 * Implement the additional methods of [[DeltaReplicatedData]] if
 * it has support for delta-CRDT replication.
 */
trait ReplicatedData {
  /**
   * The type of the concrete implementation, e.g. `GSet[A]`.
   * To be specified by subclass.
   */
  type T <: ReplicatedData

  /**
   * Monotonic merge function.
   */
  def merge(that: T): T

}

/**
 * [[ReplicatedData]] with additional support for delta-CRDT replication.
 * delta-CRDT is a way to reduce the need for sending the full state
 * for updates. For example adding element 'c' and 'd' to set {'a', 'b'} would
 * result in sending the delta {'c', 'd'} and merge that with the state on the
 * receiving side, resulting in set {'a', 'b', 'c', 'd'}.
 *
 * Learn more about this in the paper
 * <a href="paper http://arxiv.org/abs/1603.01529">Delta State Replicated Data Types</a>.
 */
trait DeltaReplicatedData extends ReplicatedData {

  /**
   * The accumulated delta of mutator operations since previous
   * [[#resetDelta]]. When the `Replicator` invokes the `modify` function
   * of the `Update` message and the user code is invoking one or more mutator
   * operations the data is collecting the delta of the operations and makes
   * it available for the `Replicator` with the [[#delta]] accessor. The
   * `modify` function shall still return the full state in the same way as
   * `ReplicatedData` without support for deltas.
   */
  def delta: T

  /**
   * Reset collection of deltas from mutator operations. When the `Replicator`
   * invokes the `modify` function of the `Update` message the delta is always
   * "reset" and when the user code is invoking one or more mutator operations the
   * data is collecting the delta of the operations and makes it available for
   * the `Replicator` with the [[#delta]] accessor. When the `Replicator` has
   * grabbed the `delta` it will invoke this method to get a clean data instance
   * without the delta.
   */
  def resetDelta: T

}

/**
 * Java API: Interface for implementing a [[ReplicatedData]] in Java.
 *
 * The type parameter `D` is a self-recursive type to be defined by the
 * concrete implementation.
 * E.g. `class TwoPhaseSet extends AbstractReplicatedData&lt;TwoPhaseSet&gt;`
 */
abstract class AbstractReplicatedData[D <: AbstractReplicatedData[D]] extends ReplicatedData {

  override type T = ReplicatedData

  /**
   * Delegates to [[#mergeData]], which must be implemented by subclass.
   */
  final override def merge(that: ReplicatedData): ReplicatedData =
    mergeData(that.asInstanceOf[D])

  /**
   * Java API: Monotonic merge function.
   */
  def mergeData(that: D): D

}

/**
 * Java API: Interface for implementing a [[DeltaReplicatedData]] in Java.
 *
 * The type parameter `D` is a self-recursive type to be defined by the
 * concrete implementation.
 * E.g. `class TwoPhaseSet extends AbstractDeltaReplicatedData&lt;TwoPhaseSet&gt;`
 */
abstract class AbstractDeltaReplicatedData[D <: AbstractDeltaReplicatedData[D]]
  extends AbstractReplicatedData[D] with DeltaReplicatedData {
}

/**
 * [[ReplicatedData]] that has support for pruning of data
 * belonging to a specific node may implement this interface.
 * When a node is removed from the cluster these methods will be
 * used by the [[Replicator]] to collapse data from the removed node
 * into some other node in the cluster.
 *
 * See process description in the 'CRDT Garbage' section of the [[Replicator]]
 * documentation.
 */
trait RemovedNodePruning extends ReplicatedData {

  /**
   * The nodes that have changed the state for this data
   * and would need pruning when such node is no longer part
   * of the cluster.
   */
  def modifiedByNodes: Set[UniqueAddress]

  /**
   * Does it have any state changes from a specific node,
   * which has been removed from the cluster.
   */
  def needPruningFrom(removedNode: UniqueAddress): Boolean

  /**
   * When the `removed` node has been removed from the cluster the state
   * changes from that node will be pruned by collapsing the data entries
   * to another node.
   */
  def prune(removedNode: UniqueAddress, collapseInto: UniqueAddress): T

  /**
   * Remove data entries from a node that has been removed from the cluster
   * and already been pruned.
   */
  def pruningCleanup(removedNode: UniqueAddress): T
}

/**
 * Marker trait for `ReplicatedData` serialized by
 * [[akka.cluster.ddata.protobuf.ReplicatedDataSerializer]].
 */
trait ReplicatedDataSerialization extends Serializable

