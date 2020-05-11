/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.{ ActorLogging, Props }
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.Internal
import akka.cluster.sharding.ShardCoordinator.Internal.ShardHomeAllocated
import akka.cluster.sharding.ShardRegion.ShardId
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }
import akka.persistence.journal.{ EventAdapter, EventSeq }

import scala.collection.mutable

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventSourcedRememberShards {
  def props(typeName: String): Props =
    Props(new EventSourcedRememberShards(typeName))

  class FromOldCoordinatorState() extends EventAdapter {
    override def manifest(event: Any): String =
      ""

    override def toJournal(event: Any): Any =
      event

    override def fromJournal(event: Any, manifest: String): EventSeq = {
      event match {
        case ShardHomeAllocated(shardId, _) =>
          EventSeq.single(shardId)
        case _ => EventSeq.empty
      }

    }
  }
}

/**
 * INTERNAL API
 *
 * FIXME add snapshotting and then the migration event adapter can be removed
 */
@InternalApi
private[akka] final class EventSourcedRememberShards(typeName: String) extends PersistentActor with ActorLogging {

  // Uses the same persistence id as the old persistent coordinator so that the old data can be migrated
  // without any user action
  override def persistenceId = s"/sharding/${typeName}Coordinator"

  private val shards = mutable.Set.empty[ShardId]

  override def receiveRecover: Receive = {
    case shardId: ShardId =>
      shards.add(shardId)
    case SnapshotOffer(_, state: Internal.State) =>
      shards ++= (state.shards.keys ++ state.unallocatedShards)
    case RecoveryCompleted =>
      log.debug("Recovery complete. Current shards {}", shards)
    case other =>
      log.error(
        "Unexpected message type [{}]. Are you migrating from persistent coordinator state store? If so you must add the migration event adapter. Shards will not be restarted.",
        other.getClass)
  }

  override def receiveCommand: Receive = {
    case RememberEntitiesCoordinatorStore.GetShards =>
      sender() ! RememberEntitiesCoordinatorStore.RememberedShards(shards.toSet)

    case RememberEntitiesCoordinatorStore.AddShard(shardId: ShardId) =>
      persistAsync(shardId) { shardId =>
        shards.add(shardId)
        sender() ! RememberEntitiesCoordinatorStore.UpdateDone(shardId)
      }
  }
}
