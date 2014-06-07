/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.cluster.UniqueAddress

/**
 * Interface for implementing a state based convergent
 * replicated data type (CvRDT).
 *
 * ReplicatedData types must be serializable with an Akka
 * Serializer. It is higly recommended to implement a serializer with
 * Protobuf or similar. The built in data types are marked with
 * [[ReplicatedDataSerialization]] and serialized with
 * [[akka.contrib.datareplication.protobuf.ReplicatedDataSerializer]].
 *
 * Serialization of the data types are used in remote messages and also
 * for creating message digests (SHA-1) to detect changes. Therefore it is
 * important that the serialization produce the same bytes for the same content.
 * For example sets and maps should be sorted deterministically in the serialization.
 */
trait ReplicatedData {
  type T <: ReplicatedData

  /**
   * Monotonic merge function.
   */
  def merge(that: T): T

}

/**
 * Java API: Interface for implementing a [[ReplicatedData]] in
 * Java.
 */
abstract class AbstractReplicatedData extends ReplicatedData {
  // it is not possible to use a more strict type, because it is erased somehow, and 
  // the implementation is anyway required to implement
  // merge(that: ReplicatedData): ReplicatedData
  type T = AbstractReplicatedData

}

/**
 * [[ReplicatedData]] that has support for pruning of data
 * belonging to a specific node may implement this interface.
 * When a node is removed from the cluster these methods will be
 * used by the [[Replicator]] to collapse data from the removed node
 * into some other node in the cluster.
 */
trait RemovedNodePruning { this: ReplicatedData ⇒

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
 * [[akka.contrib.datareplication.protobuf.ReplicatedDataSerializer]].
 */
trait ReplicatedDataSerialization extends Serializable

