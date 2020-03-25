/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSerializable
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.ShardId
import akka.dispatch.ExecutionContexts
import akka.pattern._
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
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class EventSourcedRememberEntities(eventSourcedStore: ActorRef) extends RememberEntitiesShardStore {

  // FIXME configurable
  private implicit val askTimeout: Timeout = 10.seconds

  override def addEntity(shardId: ShardId, entityId: EntityId): Future[Done] =
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.EntityStarted(entityId))
      .mapTo[EventSourcedRememberEntitiesStore.StartedAck.type]
      .map(_ => Done)(ExecutionContexts.parasitic)

  override def removeEntity(shardId: ShardId, entityId: EntityId): Future[Done] =
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.EntityStopped(entityId))
      .mapTo[EventSourcedRememberEntitiesStore.StoppedAck.type]
      .map(_ => Done)(ExecutionContexts.parasitic)

  override def getEntities(shardId: ShardId): Future[Set[EntityId]] =
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.GetEntityIds)
      .mapTo[EventSourcedRememberEntitiesStore.EntityIds]
      .map(_.ids)(ExecutionContexts.parasitic)

  override def toString: ShardId = s"${getClass.getSimpleName}($eventSourcedStore)"
}

/**
 * INTERNAL API
 */
private[akka] object EventSourcedRememberEntitiesStore {

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange extends ClusterShardingSerializable {
    val entityId: EntityId
  }

  /**
   * Persistent state of the Shard.
   */
  @SerialVersionUID(1L) final case class State private[akka] (entities: Set[EntityId] = Set.empty)
      extends ClusterShardingSerializable

  /**
   * `State` change for starting an entity in this `Shard`
   */
  @SerialVersionUID(1L) final case class EntityStarted(entityId: EntityId) extends StateChange

  case object StartedAck

  /**
   * `State` change for an entity which has terminated.
   */
  @SerialVersionUID(1L) final case class EntityStopped(entityId: EntityId) extends StateChange

  case object StoppedAck

  final case object GetEntityIds
  final case class EntityIds(ids: Set[EntityId])

  def props(typeName: String, shardId: ShardRegion.ShardId, settings: ClusterShardingSettings): Props =
    Props(new EventSourcedRememberEntitiesStore(typeName, shardId, settings))
}

/**
 * INTERNAL API
 *
 * Persistent actor keeping the state for the EventSourcedRememberEntities (enabled through `state-store-mode=persistence`).
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

  log.debug("PersistentShard starting up [{}] [{}]", typeName, shardId)
  private var state = State()
  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"
  override def journalPluginId: String = settings.journalPluginId
  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case EntityStarted(id)                 => state = state.copy(state.entities + id)
    case EntityStopped(id)                 => state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted =>
      log.debug("PersistentShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def receiveCommand: Receive = {
    case started: EntityStarted =>
      persist(started) { _ =>
        sender() ! StartedAck
        state.copy(state.entities + started.entityId)
        saveSnapshotWhenNeeded()
      }
    case stopped: EntityStopped =>
      persist(stopped) { _ =>
        sender() ! StoppedAck
        state.copy(state.entities - stopped.entityId)
        saveSnapshotWhenNeeded()
      }
    case GetEntityIds =>
      sender() ! EntityIds(state.entities)

    case e: SaveSnapshotSuccess =>
      log.debug("PersistentShard snapshot saved successfully")
      internalDeleteMessagesBeforeSnapshot(e, keepNrOfBatches, snapshotAfter)

    case SaveSnapshotFailure(_, reason) =>
      log.warning("PersistentShard snapshot failure: [{}]", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) =>
      val deleteTo = toSequenceNr - 1
      val deleteFrom = math.max(0, deleteTo - (keepNrOfBatches * snapshotAfter))
      log.debug(
        "PersistentShard messages to [{}] deleted successfully. Deleting snapshots from [{}] to [{}]",
        toSequenceNr,
        deleteFrom,
        deleteTo)
      deleteSnapshots(SnapshotSelectionCriteria(minSequenceNr = deleteFrom, maxSequenceNr = deleteTo))

    case DeleteMessagesFailure(reason, toSequenceNr) =>
      log.warning("PersistentShard messages to [{}] deletion failure: [{}]", toSequenceNr, reason.getMessage)

    case DeleteSnapshotsSuccess(m) =>
      log.debug("PersistentShard snapshots matching [{}] deleted successfully", m)

    case DeleteSnapshotsFailure(m, reason) =>
      log.warning("PersistentShard snapshots matching [{}] deletion failure: [{}]", m, reason.getMessage)

  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("PersistentShard saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }

}
