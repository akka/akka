/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic.scaladsl

import akka.Done
import akka.actor.Address
import akka.cluster.sharding.ShardRegion.ShardId

import scala.concurrent.Future

trait DynamicShardAllocationClient {

  /**
   * Update the given shard's location. The [[Address]] should
   * match one of the nodes in the cluster. If the node has not joined
   * the cluster yet it will be moved to that node after the first cluster
   * sharding rebalance.
   *
   * @param shard The shard identifier
   * @param location Location (akka node) to allocate the shard to
   * @return Confirmation that the update has been propagated to a majority of cluster nodes
   */
  def updateShardLocation(shard: ShardId, location: Address): Future[Done]
}
