/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import java.lang.{ Boolean => JBoolean, Integer => JInteger }

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardCoordinator.ActorSystemDependentAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId

import scala.collection.immutable

/**
 * Common logic for the least shard allocation strategy implementations
 *
 * INTERNAL API
 */
@InternalApi
private[akka] object AbstractLeastShardAllocationStrategy {
  import MemberStatus._
  private val LeavingClusterStatuses: Set[MemberStatus] = Set(Leaving, Exiting, Down)
  // defer rebalance when nodes are soon becoming up
  private val AvoidRebalanceWhen: Set[MemberStatus] = Set(Joining, WeaklyUp)

  type AllocationMap = Map[ActorRef, immutable.IndexedSeq[ShardId]]

  implicit object ShardSuitabilityOrdering extends Ordering[(ActorRef, Member, immutable.IndexedSeq[ShardId])] {
    override def compare(
        x: (ActorRef, Member, immutable.IndexedSeq[ShardId]),
        y: (ActorRef, Member, immutable.IndexedSeq[ShardId])): Int = {
      val (_, memberX, allocatedShardsX) = x
      val (_, memberY, allocatedShardsY) = y
      if (memberX.status != memberY.status) {
        // prefer allocating to nodes that are not on their way out of the cluster
        val xIsLeaving = LeavingClusterStatuses(memberX.status)
        val yIsLeaving = LeavingClusterStatuses(memberY.status)
        JBoolean.compare(xIsLeaving, yIsLeaving)
      } else if (memberX.appVersion != memberY.appVersion) {
        // prefer nodes with the highest rolling update app version
        memberY.appVersion.compareTo(memberX.appVersion)
      } else {
        // prefer the node with the least allocated shards
        JInteger.compare(allocatedShardsX.size, allocatedShardsY.size)
      }
    }
  }
}

/**

 *
 * INTERNAL API
 */
@InternalApi
private[akka] abstract class AbstractLeastShardAllocationStrategy extends ActorSystemDependentAllocationStrategy {
  import AbstractLeastShardAllocationStrategy._

  @volatile private var cluster: Cluster = _

  override def start(system: ActorSystem): Unit = {
    cluster = Cluster(system)
  }

  // protected for testability
  protected def selfAddress: Address = cluster.selfAddress
  protected def clusterState: CurrentClusterState = cluster.state

  protected def isAGoodTimeToRebalance: Boolean = {
    // rolling upgrade in progress
    !clusterState.hasMoreThanOneAppVersion &&
    !clusterState.members.exists(m => AvoidRebalanceWhen(m.status))
    clusterState.unreachable.isEmpty // because rebalance requires ack from all anyway
  }

  protected def mostSuitableRegion(
      currentShardAllocations: AllocationMap): (ActorRef, immutable.IndexedSeq[ShardId]) = {
    val decorated = decorate(currentShardAllocations)
    decorated.toVector.sorted(ShardSuitabilityOrdering).map { case (region, _, shards) => region -> shards }.head
  }

  private def decorate(
      currentShardAllocations: AllocationMap): Iterable[(ActorRef, Member, immutable.IndexedSeq[ShardId])] = {
    val addressToMember: Map[Address, Member] = clusterState.members.iterator.map(m => m.address -> m).toMap
    currentShardAllocations.flatMap {
      case (region, shardIds) =>
        val regionAddress = {
          if (region.path.address.hasLocalScope) selfAddress
          else region.path.address
        }

        val memberForRegion = addressToMember.get(regionAddress)
        // if the member is unknown (very unlikely but not impossible) because of view not updated yet
        // that node is ignored for this invocation
        memberForRegion.map(member => (region, member, shardIds))
    }
  }

}
