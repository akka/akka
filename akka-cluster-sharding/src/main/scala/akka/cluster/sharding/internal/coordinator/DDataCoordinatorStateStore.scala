/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.annotation.InternalApi
import akka.cluster.Cluster
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.ModifyFailure
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.UpdateSuccess
import akka.cluster.ddata.Replicator.UpdateTimeout
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator
import akka.util.PrettyDuration._
import akka.util.Timeout

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DDataCoordinatorStateStore {
  def props(typeName: String, settings: ClusterShardingSettings, replicator: ActorRef, majorityMinCap: Int): Props =
    Props(new DDataCoordinatorStateStore(typeName, settings, replicator, majorityMinCap))
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class DDataCoordinatorStateStore(
    typeName: String,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  import ShardCoordinator.Internal._
  import akka.cluster.ddata.Replicator.Update

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  private implicit val node: Cluster = Cluster(context.system)
  private implicit val selfUniqueAddress: SelfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)
  private val CoordinatorStateKey = LWWRegisterKey[State](s"${typeName}CoordinatorState")
  private val initEmptyState = State.empty.withRememberEntities(settings.rememberEntities)

  // FIXME now we are maintaining two copies of the state in different actors, should be just send the updated state instead of the events?
  private var state = initEmptyState
  private val maxRetries = 3

  if (log.isDebugEnabled) {
    log.debug(
      "Starting up DDataCoordinatorStateStore, write timeout: [{}], read timeout: [{}], majority min cap: [{}]",
      settings.tuningParameters.waitingForStateTimeout.pretty,
      settings.tuningParameters.updatingStateTimeout.pretty,
      majorityMinCap)
  }

  // FIXME load state and possibly entity ids eagerly instead of waiting for request, throw away on first request
  /* private var terminating = false
  private var getShardHomeRequests: Set[(ActorRef, GetShardHome)] = Set.empty */

  /*
   original impl would do
    if (rememberEntities)
    replicator ! Subscribe(AllShardsKey, self)
   */

  override def receive: Receive = idle()

  private def idle(): Receive = {
    case CoordinatorStateStore.GetInitialState     => onGetInitialState(sender())
    case update: CoordinatorStateStore.UpdateState => onStateUpdate(update.event, sender())
  }

  private def readMajorityAskTimeout = readMajority.timeout * 2

  private def onGetInitialState(replyTo: ActorRef): Unit = {
    implicit val timeout: Timeout = readMajorityAskTimeout
    replicator ! Get(CoordinatorStateKey, readMajority)

    // FIXME we should at least not make these receives sequential since the coordinator sends both immediately
    context.become({
      case g @ GetSuccess(CoordinatorStateKey, _) =>
        state = g.get(CoordinatorStateKey).value.withRememberEntities(settings.rememberEntities)
        replyTo ! CoordinatorStateStore.InitialState(state)
        context.become(idle())
      case GetFailure(CoordinatorStateKey, _) =>
        log.error(
          "The ShardCoordinator was unable to get an initial state within 'waiting-for-state-timeout': {} millis (retrying). Has ClusterSharding been started on all nodes?",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        onGetInitialState(replyTo)

      case NotFound(CoordinatorStateKey, _) =>
        // FIXME is this right, not found means cold start? retry or return empty?
        replyTo ! CoordinatorStateStore.InitialState(state)
    })
  }

  private def onStateUpdate(event: DomainEvent, replyTo: ActorRef): Unit = {
    state = state.updated(event)
    val update = () =>
      replicator ! Update(
        CoordinatorStateKey,
        LWWRegister(selfUniqueAddress, initEmptyState),
        writeMajority,
        Some(event)) { reg =>
        reg.withValueOf(state)
      }

    context.become(waitingForStateUpdate(event, replyTo, maxRetries, update))
  }

  // this state will stash all messages until it receives UpdateSuccess
  private def waitingForStateUpdate[E <: DomainEvent](
      evt: E,
      replyTo: ActorRef,
      retriesLeft: Int,
      retry: () => Unit): Receive = {
    case UpdateSuccess(CoordinatorStateKey, Some(`evt`)) =>
      log.debug("The coordinator state was successfully updated with {}", evt)
      replyTo ! CoordinatorStateStore.StateUpdateDone(evt)
      context.become(idle())

    case UpdateTimeout(CoordinatorStateKey, Some(`evt`)) =>
      log.error(
        "The ShardCoordinator was unable to update a distributed state within 'updating-state-timeout': {} millis. " +
        "Perhaps the ShardRegion has not started on all active nodes yet? event={}, stopping",
        writeMajority.timeout.toMillis,
        evt)
      // repeat until UpdateSuccess
      retry()

    case ModifyFailure(key, error, cause, _) =>
      // FIXME When would the modify function throw?
      if (retriesLeft > 0) {
        log.error(
          cause,
          "Unable to update a distributed state {} with error {} and event {}, retrying",
          key,
          error,
          evt)
        retry()
        waitingForStateUpdate(evt, replyTo, retriesLeft - 1, retry)
      } else {
        log.error(
          cause,
          "Unable to update a distributed state {} with error {} and event {} after {} retries, stopping",
          key,
          error,
          evt,
          maxRetries)
        context.stop(self)
      }

  }

}
