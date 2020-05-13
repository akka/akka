/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.actor.ActorLogging
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSerializable
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.persistence.DeleteMessagesFailure
import akka.persistence.DeleteMessagesSuccess
import akka.persistence.DeleteSnapshotsFailure
import akka.persistence.DeleteSnapshotsSuccess
import akka.persistence.PersistentActor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess
import akka.persistence.SnapshotOffer
import akka.persistence.SnapshotSelectionCriteria

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class EventSourcedRememberEntitiesProvider(typeName: String, settings: ClusterShardingSettings)
    extends RememberEntitiesProvider {

  // this is backed by an actor using the same events, at the serailisation level, as the now removed PersistentShard when state-store-mode=persistence
  // new events can be added but the old events should continue to be handled
  override def shardStoreProps(shardId: ShardId): Props =
    EventSourcedRememberEntitiesStore.props(typeName, shardId, settings)

  // Note that this one is never used for the deprecated persistent state store mode, only when state store is ddata
  // combined with eventsourced remember entities storage
  override def coordinatorStoreProps(): Props =
    EventSourcedRememberShards.props(typeName, settings)
}

/**
 * INTERNAL API
 *
 */
private[akka] object EventSourcedRememberEntitiesStore {

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange extends ClusterShardingSerializable

  /**
   * Persistent state of the Shard.
   */
  final case class State private[akka] (entities: Set[EntityId] = Set.empty) extends ClusterShardingSerializable

  /**
   * TODO remove this and just turn it into an EntitiesStarted
   * `State` change for starting an entity in this `Shard`
   */
  final case class EntityStarted(entityId: EntityId) extends StateChange

  final case class EntitiesStarted(entities: Set[String]) extends StateChange

  case object StartedAck

  /**
   * `State` change for an entity which has terminated.
   */
  final case class EntityStopped(entityId: EntityId) extends StateChange

  def props(typeName: String, shardId: ShardRegion.ShardId, settings: ClusterShardingSettings): Props =
    Props(new EventSourcedRememberEntitiesStore(typeName, shardId, settings))
}

/**
 * INTERNAL API
 *
 * Persistent actor keeping the state for Akka Persistence backed remember entities (enabled through `state-store-mode=persistence`).
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] final class EventSourcedRememberEntitiesStore(
    typeName: String,
    shardId: ShardRegion.ShardId,
    settings: ClusterShardingSettings)
    extends PersistentActor
    with ActorLogging {

  import EventSourcedRememberEntitiesStore._
  import settings.tuningParameters._

  log.debug("Starting up EventSourcedRememberEntitiesStore")
  private var state = State()
  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"
  override def journalPluginId: String = settings.journalPluginId
  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case EntityStarted(id)                 => state = state.copy(state.entities + id)
    case EntitiesStarted(ids)              => state = state.copy(state.entities ++ ids)
    case EntityStopped(id)                 => state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted =>
      log.debug("Recovery completed for shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def receiveCommand: Receive = {
    case RememberEntitiesShardStore.AddEntities(ids) =>
      persist(EntitiesStarted(ids)) { started =>
        sender() ! RememberEntitiesShardStore.UpdateDone(ids)
        state.copy(state.entities ++ started.entities)
        saveSnapshotWhenNeeded()
      }
    case RememberEntitiesShardStore.RemoveEntity(id) =>
      persist(EntityStopped(id)) { stopped =>
        sender() ! RememberEntitiesShardStore.UpdateDone(Set(id))
        state.copy(state.entities - stopped.entityId)
        saveSnapshotWhenNeeded()
      }
    case RememberEntitiesShardStore.GetEntities =>
      sender() ! RememberEntitiesShardStore.RememberedEntities(state.entities)

    case e: SaveSnapshotSuccess =>
      log.debug("Snapshot saved successfully")
      internalDeleteMessagesBeforeSnapshot(e, keepNrOfBatches, snapshotAfter)

    case SaveSnapshotFailure(_, reason) =>
      log.warning("Snapshot failure: [{}]", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) =>
      val deleteTo = toSequenceNr - 1
      val deleteFrom = math.max(0, deleteTo - (keepNrOfBatches * snapshotAfter))
      log.debug(
        "Messages to [{}] deleted successfully. Deleting snapshots from [{}] to [{}]",
        toSequenceNr,
        deleteFrom,
        deleteTo)
      deleteSnapshots(SnapshotSelectionCriteria(minSequenceNr = deleteFrom, maxSequenceNr = deleteTo))

    case DeleteMessagesFailure(reason, toSequenceNr) =>
      log.warning("Messages to [{}] deletion failure: [{}]", toSequenceNr, reason.getMessage)

    case DeleteSnapshotsSuccess(m) =>
      log.debug("Snapshots matching [{}] deleted successfully", m)

    case DeleteSnapshotsFailure(m, reason) =>
      log.warning("Snapshots matching [{}] deletion failure: [{}]", m, reason.getMessage)

  }

  override def postStop(): Unit = {
    super.postStop()
    log.debug("Store stopping")
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }

}
