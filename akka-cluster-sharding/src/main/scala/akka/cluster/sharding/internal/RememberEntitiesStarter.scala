/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.Timers
import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.Shard
import akka.cluster.sharding.ShardRegion

import scala.collection.immutable.Set

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object RememberEntityStarter {
  def props(
      region: ActorRef,
      shard: ActorRef,
      shardId: ShardRegion.ShardId,
      ids: Set[ShardRegion.EntityId],
      settings: ClusterShardingSettings) =
    Props(new RememberEntityStarter(region, shard, shardId, ids, settings))

  private case object Tick extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API: Actor responsible for starting entities when rememberEntities is enabled
 */
@InternalApi
private[akka] final class RememberEntityStarter(
    region: ActorRef,
    shard: ActorRef,
    shardId: ShardRegion.ShardId,
    ids: Set[ShardRegion.EntityId],
    settings: ClusterShardingSettings)
    extends Actor
    with ActorLogging
    with Timers {

  import RememberEntityStarter.Tick

  private var waitingForAck = ids
  private var entitiesMoved = Set.empty[ShardRegion.ShardId]

  sendStart(ids)

  val tickTask = {
    val resendInterval = settings.tuningParameters.retryInterval
    timers.startTimerWithFixedDelay(Tick, Tick, resendInterval)
  }

  def sendStart(ids: Set[ShardRegion.EntityId]): Unit = {
    // these go through the region rather the directly to the shard
    // so that shard mapping changes are picked up
    ids.foreach(id => region ! ShardRegion.StartEntity(id))
  }

  override def receive: Receive = {
    case ShardRegion.StartEntityAck(entityId, ackFromShardId) =>
      waitingForAck -= entityId
      if (shardId != ackFromShardId) entitiesMoved += entityId
      if (waitingForAck.isEmpty) {
        if (entitiesMoved.nonEmpty) shard ! Shard.ShardIdsMoved(ids)
        context.stop(self)
      }

    case Tick =>
      sendStart(waitingForAck)

  }
}
