/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable
import scala.concurrent.Future

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.internal.ClusterShardAllocationMixin
import akka.cluster.sharding.internal.ClusterShardAllocationMixin.ShardSuitabilityOrdering
import akka.routing.ConsistentHash

object ConsistentHashingShardAllocationStrategy {
  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])
}

/**
 * [[akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy]] that is using consistent
 * hashing. This can be useful when shards with the same shard id for different entity types
 * should be best effort collocated to the same nodes.
 *
 * When adding or removing nodes it will rebalance according to the new consistent hashing,
 * but that means that only a few shards will be rebalanced and others remain on the same
 * location.
 *
 * A good explanation of Consistent Hashing:
 * https://tom-e-white.com/2007/11/consistent-hashing.html
 *
 */
class ConsistentHashingShardAllocationStrategy(rebalanceLimit: Int)
    extends ActorSystemDependentAllocationStrategy
    with ClusterShardAllocationMixin {
  import ConsistentHashingShardAllocationStrategy.emptyRebalanceResult

  @volatile private var cluster: Cluster = _

  private val virtualNodesFactor = 10
  private var hashedByNodes: Vector[Address] = Vector.empty
  private var consistentHashing: ConsistentHash[Address] = ConsistentHash(Nil, virtualNodesFactor)

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
  }

  override protected def clusterState: CurrentClusterState = cluster.state
  override protected def selfMember: Member = cluster.selfMember

  override def allocateShard(
      requester: ActorRef,
      shardId: ShardId,
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]]): Future[ActorRef] = {
    val nodes = nodesForRegions(currentShardAllocations)
    if (nodes != hashedByNodes)
      updateHashing(nodes)
    val node = consistentHashing.nodeFor(shardId)
    currentShardAllocations.keysIterator.find(region => nodeForRegion(region) == node) match {
      case Some(region) => Future.successful(region)
      case None =>
        throw new IllegalStateException(s"currentShardAllocations should include region for node [$node]")
    }
  }

  override def rebalance(
      currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {

    val sortedRegionEntries = regionEntriesFor(currentShardAllocations).toVector.sorted(ShardSuitabilityOrdering)
    if (!isAGoodTimeToRebalance(sortedRegionEntries)) {
      emptyRebalanceResult
    } else {

      val nodes = nodesForRegions(currentShardAllocations)
      if (nodes != hashedByNodes)
        updateHashing(nodes)

      val regionByNode = currentShardAllocations.keysIterator.map(region => nodeForRegion(region) -> region).toMap

      var result = Set.empty[String]

      def lessThanLimit: Boolean =
        rebalanceLimit <= 0 || result.size < rebalanceLimit

      currentShardAllocations
      // deterministic order, at least easier to test
      .toVector.sortBy { case (region, _) => nodeForRegion(region) }(Address.addressOrdering).foreach {
        case (currentRegion, shardIds) =>
          shardIds.foreach { shardId =>
            if (lessThanLimit && !rebalanceInProgress.contains(shardId)) {
              val node = consistentHashing.nodeFor(shardId)
              regionByNode.get(node) match {
                case Some(region) =>
                  if (region != currentRegion)
                    result += shardId
                case None =>
                  throw new IllegalStateException(s"currentShardAllocations should include region for node [$node]")
              }
            }
          }
      }

      Future.successful(result)
    }
  }

  private def nodesForRegions(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Vector[Address] = {
    currentShardAllocations.keysIterator.map(nodeForRegion).toVector
  }

  private def nodeForRegion(region: ActorRef): Address =
    if (region.path.address.hasLocalScope) selfMember.address
    else region.path.address

  private def updateHashing(nodes: Vector[Address]): Unit = {
    hashedByNodes = nodes
    consistentHashing = ConsistentHash(nodes, virtualNodesFactor)
  }

}
