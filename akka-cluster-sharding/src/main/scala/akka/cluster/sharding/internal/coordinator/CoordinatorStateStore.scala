/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.Done
import akka.actor.ActorRef
import akka.cluster.sharding.ShardCoordinator.Internal.DomainEvent
import akka.cluster.sharding.ShardCoordinator.Internal.State
import akka.cluster.sharding.ShardRegion.ShardId

import scala.concurrent.Future

trait CoordinatorStateStore {
  def getInitialState(): Future[State]
  // FIXME just events for now
  def storeStateUpdate(event: DomainEvent): Future[Done]
}

trait CordinatorRememberEntitiesStore {
  def getShards(): Future[Set[ShardId]]
  def addShard(shardId: ShardId): Future[Done]
}
