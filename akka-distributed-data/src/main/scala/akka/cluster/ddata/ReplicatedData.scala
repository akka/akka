/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
 * [[ReplicatedData]] that has support for pruning of data
 * belonging to a specific node may implement this interface.
 * When a node is removed from the cluster these methods will be
 * used by the [[Replicator]] to collapse data from the removed node
 * into some other node in the cluster.
 */
trait RemovedNodePruning extends ReplicatedData {

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

