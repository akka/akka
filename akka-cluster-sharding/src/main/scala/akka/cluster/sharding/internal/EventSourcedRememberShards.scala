/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.ShardId
import akka.persistence.PersistentActor

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventSourcedRememberShards {
  def props(typeName: String): Props =
    Props(new EventSourcedRememberShards(typeName))
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class EventSourcedRememberShards(typeName: String) extends PersistentActor {

  override val persistenceId: String = s"$typeName-remember-entitites"

  private var shards = Set.empty[ShardId]

  override def receiveRecover: Receive = {
    case shardId: ShardId =>
      // FIXME optimize for adding rather than reading (which is done only once)
      shards += shardId
  }

  override def receiveCommand: Receive = {
    case RememberEntitiesCoordinatorStore.GetShards =>
      sender() ! RememberEntitiesCoordinatorStore.RememberedShards(shards)

    case RememberEntitiesCoordinatorStore.AddShard(shardId: ShardId) =>
      persistAsync(shardId) { shardId =>
        shards += shardId
        sender() ! RememberEntitiesCoordinatorStore.UpdateDone(shardId)
      }
  }

}
