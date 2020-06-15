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
private[akka] object EventSourcedRememberEntitiesShardStore {

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange extends ClusterShardingSerializable

  /**
   * Persistent state of the Shard.
   */
  final case class State private[akka] (entities: Set[EntityId] = Set.empty) extends ClusterShardingSerializable

  /**
   * `State` change for starting a set of entities in this `Shard`
   */
  final case class EntitiesStarted(entities: Set[EntityId]) extends StateChange

  case object StartedAck

  /**
   * `State` change for an entity which has terminated.
   */
  final case class EntitiesStopped(entities: Set[EntityId]) extends StateChange

  def props(typeName: String, shardId: ShardRegion.ShardId, settings: ClusterShardingSettings): Props =
    Props(new EventSourcedRememberEntitiesShardStore(typeName, shardId, settings))
}

/**
 * INTERNAL API
 *
 * Persistent actor keeping the state for Akka Persistence backed remember entities (enabled through `state-store-mode=persistence`).
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
@InternalApi
private[akka] final class EventSourcedRememberEntitiesShardStore(
    typeName: String,
    shardId: ShardRegion.ShardId,
    settings: ClusterShardingSettings)
    extends PersistentActor
    with ActorLogging {

  import EventSourcedRememberEntitiesShardStore._
  import settings.tuningParameters._

  private val maxUpdatesPerWrite = context.system.settings.config
    .getInt("akka.cluster.sharding.event-sourced-remember-entities-store.max-updates-per-write")

  log.debug("Starting up EventSourcedRememberEntitiesStore")
  private var state = State()
  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"
  override def journalPluginId: String = settings.journalPluginId
  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case EntitiesStarted(ids)              => state = state.copy(state.entities.union(ids))
    case EntitiesStopped(ids)              => state = state.copy(state.entities.diff(ids))
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted =>
      log.debug("Recovery completed for shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def receiveCommand: Receive = {

    case RememberEntitiesShardStore.Update(started, stopped) =>
      val events =
        (if (started.nonEmpty) EntitiesStarted(started) :: Nil else Nil) :::
        (if (stopped.nonEmpty) EntitiesStopped(stopped) :: Nil else Nil)
      var left = events.size
      if (left <= maxUpdatesPerWrite) {
        // optimized when batches are small
        persistAll(events) { _ =>
          left -= 1
          if (left == 0) {
            sender() ! RememberEntitiesShardStore.UpdateDone(started, stopped)
            state.copy(state.entities.union(started).diff(stopped))
            saveSnapshotWhenNeeded()
          }
        }
      } else {
        // split up in several writes so we don't hit journal limit
        events
          .grouped(maxUpdatesPerWrite)
          .foreach(group =>
            persistAll(group) { _ =>
              left -= 1
              if (left == 0) {
                sender() ! RememberEntitiesShardStore.UpdateDone(started, stopped)
                state.copy(state.entities.union(started).diff(stopped))
                saveSnapshotWhenNeeded()
              }
            })
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
