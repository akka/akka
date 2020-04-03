/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.cluster.sharding.ClusterShardingSerializable
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.ShardRegion.ShardId
import akka.persistence.PersistentActor
import ShardCoordinator.Internal.State

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventSourcedCoordinatorStore {
  // DomainEvents for the persistent state of the event sourced ShardCoordinator
  sealed trait DomainEvent extends ClusterShardingSerializable
  final case class ShardRegionRegistered(region: ActorRef) extends DomainEvent
  final case class ShardRegionProxyRegistered(regionProxy: ActorRef) extends DomainEvent
  final case class ShardRegionTerminated(region: ActorRef) extends DomainEvent
  final case class ShardRegionProxyTerminated(regionProxy: ActorRef) extends DomainEvent
  final case class ShardHomeAllocated(shard: ShardId, region: ActorRef) extends DomainEvent
  final case class ShardHomeDeallocated(shard: ShardId) extends DomainEvent
}

/**
 * INTERNAL API
 *
 * Implementation for the 'persistence' coordinator state store mode deprecated in 2.6.0 as well as remember entities
 */
@InternalApi
private[akka] class EventSourcedCoordinatorStore(typeName: String, settings: ClusterShardingSettings)
    extends PersistentActor
    with ActorLogging {

  import EventSourcedCoordinatorStore._

  private var state: State = State.empty

  override def persistenceId = s"/sharding/${typeName}Coordinator"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case evt: DomainEvent =>
      log.debug("receiveRecover {}", evt)
      evt match {
        case _: ShardRegionRegistered =>
          state = state.updated(evt)
        case _: ShardRegionProxyRegistered =>
          state = state.updated(evt)
        case ShardRegionTerminated(region) =>
          if (state.regions.contains(region))
            state = state.updated(evt)
          else {
            log.debug(
              "ShardRegionTerminated, but region {} was not registered. This inconsistency is due to that " +
              " some stored ActorRef in Akka v2.3.0 and v2.3.1 did not contain full address information. It will be " +
              "removed by later watch.",
              region)
          }
        case ShardRegionProxyTerminated(proxy) =>
          if (state.regionProxies.contains(proxy))
            state = state.updated(evt)
        case _: ShardHomeAllocated =>
          state = state.updated(evt)
        case _: ShardHomeDeallocated =>
          state = state.updated(evt)
      }

    case SnapshotOffer(_, st: State) =>
      log.debug("receiveRecover SnapshotOffer {}", st)
      state = st.withRememberEntities(settings.rememberEntities)
      //Old versions of the state object may not have unallocatedShard set,
      // thus it will be null.
      if (state.unallocatedShards == null)
        state = state.copy(unallocatedShards = Set.empty)

    case RecoveryCompleted =>
      state = state.withRememberEntities(settings.rememberEntities)
      watchStateActors()
  }

  override def receive: Receive = ???
}

/*

/**
 * Singleton coordinator that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
@deprecated("Use `ddata` mode, persistence mode is deprecated.", "2.6.0")
class PersistentShardCoordinator(
    override val typeName: String,
    settings: ClusterShardingSettings,
    allocationStrategy: ShardCoordinator.ShardAllocationStrategy)
    extends ShardCoordinator(settings, allocationStrategy)
    with PersistentActor {
  import ShardCoordinator.Internal._
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Coordinator"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case evt: DomainEvent =>
      log.debug("receiveRecover {}", evt)
      evt match {
        case _: ShardRegionRegistered =>
          state = state.updated(evt)
        case _: ShardRegionProxyRegistered =>
          state = state.updated(evt)
        case ShardRegionTerminated(region) =>
          if (state.regions.contains(region))
            state = state.updated(evt)
          else {
            log.debug(
              "ShardRegionTerminated, but region {} was not registered. This inconsistency is due to that " +
              " some stored ActorRef in Akka v2.3.0 and v2.3.1 did not contain full address information. It will be " +
              "removed by later watch.",
              region)
          }
        case ShardRegionProxyTerminated(proxy) =>
          if (state.regionProxies.contains(proxy))
            state = state.updated(evt)
        case _: ShardHomeAllocated =>
          state = state.updated(evt)
        case _: ShardHomeDeallocated =>
          state = state.updated(evt)
      }

    case SnapshotOffer(_, st: State) =>
      log.debug("receiveRecover SnapshotOffer {}", st)
      state = st.withRememberEntities(settings.rememberEntities)
      //Old versions of the state object may not have unallocatedShard set,
      // thus it will be null.
      if (state.unallocatedShards == null)
        state = state.copy(unallocatedShards = Set.empty)

    case RecoveryCompleted =>
      state = state.withRememberEntities(settings.rememberEntities)
      watchStateActors()
  }

  override def receiveCommand: Receive = waitingForStateInitialized

  def waitingForStateInitialized: Receive =
    ({
      case ShardCoordinator.Internal.Terminate =>
        log.debug("Received termination message before state was initialized")
        context.stop(self)

      case StateInitialized =>
        stateInitialized()
        context.become(active.orElse[Any, Unit](receiveSnapshotResult))

    }: Receive).orElse[Any, Unit](receiveTerminated).orElse[Any, Unit](receiveSnapshotResult)

  def receiveSnapshotResult: Receive = {
    case e: SaveSnapshotSuccess =>
      log.debug("Persistent snapshot saved successfully")
      internalDeleteMessagesBeforeSnapshot(e, keepNrOfBatches, snapshotAfter)

    case SaveSnapshotFailure(_, reason) =>
      log.warning("Persistent snapshot failure: {}", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) =>
      log.debug("Persistent messages to {} deleted successfully", toSequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = toSequenceNr - 1))

    case DeleteMessagesFailure(reason, toSequenceNr) =>
      log.warning("Persistent messages to {} deletion failure: {}", toSequenceNr, reason.getMessage)

    case DeleteSnapshotsSuccess(m) =>
      log.debug("Persistent snapshots matching {} deleted successfully", m)

    case DeleteSnapshotsFailure(m, reason) =>
      log.warning("Persistent snapshots matching {} deletion failure: {}", m, reason.getMessage)
  }

  def update[E <: DomainEvent](evt: E)(f: E => Unit): Unit = {
    saveSnapshotWhenNeeded()
    persist(evt)(f)
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }
}
 */
