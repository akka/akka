/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import java.net.URLEncoder

import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
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

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class EventSourcedRememberEntitiesStoreProvider(
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings)
    extends RememberEntitiesShardStoreProvider {

  override def createStoreForShard(shardId: ShardId): RememberEntitiesShardStore = {
    // one persistent store per shard
    val name = URLEncoder.encode(s"Sharding-$typeName-Store-$shardId", "utf-8")

    val store = system
      .asInstanceOf[ExtendedActorSystem]
      .systemActorOf(EventSourcedRememberEntitiesStore.props(typeName, shardId, settings), name)
    // FIXME should shard notice if store crashes? or is failing reads/writes crashing the shard good enough?
    new EventSourcedRememberEntities(store, settings)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class EventSourcedRememberEntities(eventSourcedStore: ActorRef, settings: ClusterShardingSettings)
    extends RememberEntitiesShardStore {

  override def addEntity(entityId: EntityId): Future[Done] = {
    implicit val askTimeout: Timeout = settings.tuningParameters.updatingStateTimeout
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.EntityStarted(entityId))
      .mapTo[EventSourcedRememberEntitiesStore.StartedAck.type]
      .map(_ => Done)(ExecutionContexts.parasitic)
  }

  override def removeEntity(entityId: EntityId): Future[Done] = {
    implicit val askTimeout: Timeout = settings.tuningParameters.updatingStateTimeout
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.EntityStopped(entityId))
      .mapTo[EventSourcedRememberEntitiesStore.StoppedAck.type]
      .map(_ => Done)(ExecutionContexts.parasitic)
  }

  override def getEntities(): Future[Set[EntityId]] = {
    implicit val askTimeout: Timeout = settings.tuningParameters.waitingForStateTimeout
    (eventSourcedStore ? EventSourcedRememberEntitiesStore.GetEntityIds)
      .mapTo[EventSourcedRememberEntitiesStore.EntityIds]
      .map(_.ids)(ExecutionContexts.parasitic)
  }

  override def stop(): Unit = {
    eventSourcedStore ! EventSourcedRememberEntitiesStore.StopStore
  }

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

  case object GetEntityIds
  final case class EntityIds(ids: Set[EntityId])

  case object StopStore

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

  log.debug("Starting up [{}] [{}]", typeName, shardId)
  private var state = State()
  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"
  override def journalPluginId: String = settings.journalPluginId
  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case EntityStarted(id)                 => state = state.copy(state.entities + id)
    case EntityStopped(id)                 => state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted =>
      log.debug("Recovery completed for shard [{}] with [{}] entities", shardId, state.entities.size)
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

    case StopStore =>
      log.debug("Store stopping")
      context.stop(self)
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }

}
