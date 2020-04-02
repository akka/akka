/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.Done
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.ClusterEvent
import akka.cluster.ClusterEvent.ClusterShuttingDown
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.Key
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.ReplicatedData
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.GetFailure
import akka.cluster.ddata.Replicator.GetSuccess
import akka.cluster.ddata.Replicator.NotFound
import akka.cluster.ddata.Replicator.ReadMajority
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.ddata.Replicator.WriteMajority
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator
import akka.cluster.sharding.ShardRegion.ShardId
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class DDataStateStore(
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings,
    allocationStrategy: ShardCoordinator.ShardAllocationStrategy,
    replicator: ActorRef,
    majorityMinCap: Int,
    rememberEntities: Boolean)
    extends CoordinatorStateStore with CordinatorRememberEntitiesStore {

  val log = Logging.withMarker(system, getClass)

  import ShardCoordinator.Internal._
  import akka.cluster.ddata.Replicator.Update

  private implicit val ec: ExecutionContext = system.dispatcher
  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  private implicit val node = Cluster(system)
  private implicit val selfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)
  private val CoordinatorStateKey = LWWRegisterKey[State](s"${typeName}CoordinatorState")
  private val initEmptyState = State.empty.withRememberEntities(settings.rememberEntities)

  private val AllShardsKey = GSetKey[ShardId](s"shard-${typeName}-all")

  /* private var terminating = false
  private var getShardHomeRequests: Set[(ActorRef, GetShardHome)] = Set.empty */

  private def readMajorityAskTimeout = readMajority.timeout * 2

  override def getShards(): Future[Set[ShardId]] = {
    implicit val timeout: Timeout = readMajorityAskTimeout
    (replicator ? Get(AllShardsKey, readMajority)).flatMap {
      case g @ GetSuccess(AllShardsKey, _) =>
        Future.successful(g.get(AllShardsKey).elements)

      case GetFailure(AllShardsKey, _) =>
        log.error(
          "The ShardCoordinator was unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        getShards()

      case NotFound(AllShardsKey, _) =>
        // remember entities cold start
        Future.successful(Set.empty)
    }
  }

  override def addShard(shardId: ShardId): Future[Done] = ???

  override def getInitialState(): Future[State] = {
      implicit val timeout: Timeout = readMajorityAskTimeout

   (replicator ? Get(CoordinatorStateKey, readMajority)).flatMap {
      case g @ GetSuccess(CoordinatorStateKey, _) =>
        val state = g.get(CoordinatorStateKey).value.withRememberEntities(settings.rememberEntities)
        Future.successful(state)
      case GetFailure(CoordinatorStateKey, _) =>
        log.error(
          "The ShardCoordinator was unable to get an initial state within 'waiting-for-state-timeout': {} millis (retrying). Has ClusterSharding been started on all nodes?",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        getInitialState()
      case NotFound(CoordinatorStateKey, _) =>
        // FIXME retry?
        ???
    }
  }

  override def storeStateUpdate(event: DomainEvent): Future[Done] = ???
}

/* FIXME these
  if (rememberEntities)
    replicator ! Subscribe(AllShardsKey, self)

  node.subscribe(self, ClusterEvent.InitialStateAsEvents, ClusterShuttingDown.getClass)

  // get state from ddata replicator, repeat until GetSuccess
  getCoordinatorState()
  getAllShards()

  override def receive: Receive = waitingForState(allKeys)





  // this state will stash all messages until it receives UpdateSuccess
  def waitingForUpdate[E <: DomainEvent](
      evt: E,
      afterUpdateCallback: E => Unit,
      remainingKeys: Set[Key[ReplicatedData]]): Receive = {
    case UpdateSuccess(CoordinatorStateKey, Some(`evt`)) =>
      log.debug("The coordinator state was successfully updated with {}", evt)
      val newRemainingKeys = remainingKeys - CoordinatorStateKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(CoordinatorStateKey, Some(`evt`)) =>
      log.error(
        "The ShardCoordinator was unable to update a distributed state within 'updating-state-timeout': {} millis ({}). " +
        "Perhaps the ShardRegion has not started on all active nodes yet? event={}",
        writeMajority.timeout.toMillis,
        if (terminating) "terminating" else "retrying",
        evt)
      if (terminating) {
        context.stop(self)
      } else {
        // repeat until UpdateSuccess
        sendCoordinatorStateUpdate(evt)
      }

    case UpdateSuccess(AllShardsKey, Some(newShard: String)) =>
      log.debug("The coordinator shards state was successfully updated with {}", newShard)
      val newRemainingKeys = remainingKeys - AllShardsKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(AllShardsKey, Some(newShard: String)) =>
      log.error(
        "The ShardCoordinator was unable to update shards distributed state within 'updating-state-timeout': {} millis ({}), event={}",
        writeMajority.timeout.toMillis,
        if (terminating) "terminating" else "retrying",
        evt)
      if (terminating) {
        context.stop(self)
      } else {
        // repeat until UpdateSuccess
        sendAllShardsUpdate(newShard)
      }

    case ModifyFailure(key, error, cause, _) =>
      log.error(
        cause,
        "The ShardCoordinator was unable to update a distributed state {} with error {} and event {}. {}",
        key,
        error,
        evt,
        if (terminating) "Coordinator will be terminated due to Terminate message received"
        else "Coordinator will be restarted")
      if (terminating) {
        context.stop(self)
      } else {
        throw cause
      }

    case g @ GetShardHome(shard) =>
      if (!handleGetShardHome(shard))
        stashGetShardHomeRequest(sender(), g) // must wait for update that is in progress

    case ShardCoordinator.Internal.Terminate =>
      log.debug("Received termination message while waiting for update")
      terminating = true
      stash()

    case _ => stash()
  }

  private def unbecomeAfterUpdate[E <: DomainEvent](evt: E, afterUpdateCallback: E => Unit): Unit = {
    context.unbecome()
    afterUpdateCallback(evt)
    log.debug("New coordinator state after [{}]: [{}]", evt, state)
    unstashGetShardHomeRequests()
    unstashAll()
  }


  def activate() = {
    context.become(active)
    log.info("ShardCoordinator was moved to the active state {}", state)
  }

  override def active: Receive =
    if (rememberEntities) {
      ({
        case chg @ Changed(AllShardsKey) =>
          shards = chg.get(AllShardsKey).elements
      }: Receive).orElse[Any, Unit](super.active)
    } else
      super.active

  def update[E <: DomainEvent](evt: E)(f: E => Unit): Unit = {
    sendCoordinatorStateUpdate(evt)
    evt match {
      case s: ShardHomeAllocated if rememberEntities && !shards(s.shard) =>
        sendAllShardsUpdate(s.shard)
        context.become(waitingForUpdate(evt, f, allKeys), discardOld = false)
      case _ =>
        // no update of shards, already known
        context.become(waitingForUpdate(evt, f, Set(CoordinatorStateKey)), discardOld = false)
    }

  }

  def getCoordinatorState(): Unit = {
    replicator ! Get(CoordinatorStateKey, readMajority)
  }

  def getAllShards(): Unit = {
    if (rememberEntities)
      replicator ! Get(AllShardsKey, readMajority)
  }

  def sendCoordinatorStateUpdate(evt: DomainEvent) = {
    val s = state.updated(evt)
    log.debug("Publishing new coordinator state [{}]", state)
    replicator ! Update(CoordinatorStateKey, LWWRegister(selfUniqueAddress, initEmptyState), writeMajority, Some(evt)) {
      reg =>
        reg.withValueOf(s)
    }
  }

  def sendAllShardsUpdate(newShard: String) = {
    replicator ! Update(AllShardsKey, GSet.empty[String], writeMajority, Some(newShard))(_ + newShard)
  }

}

/*
/**
 * Singleton coordinator (with state based on ddata) that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class DDataShardCoordinator(
    override val typeName: String,
    settings: ClusterShardingSettings,
    allocationStrategy: ShardCoordinator.ShardAllocationStrategy,
    replicator: ActorRef,
    majorityMinCap: Int,
    rememberEntities: Boolean)
    extends ShardCoordinator(settings, allocationStrategy)
    with Stash {
  import ShardCoordinator.Internal._
  import akka.cluster.ddata.Replicator.Update

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  implicit val node = Cluster(context.system)
  private implicit val selfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)
  val CoordinatorStateKey = LWWRegisterKey[State](s"${typeName}CoordinatorState")
  val initEmptyState = State.empty.withRememberEntities(settings.rememberEntities)

  val AllShardsKey = GSetKey[String](s"shard-${typeName}-all")
  val allKeys: Set[Key[ReplicatedData]] =
    if (rememberEntities) Set(CoordinatorStateKey, AllShardsKey) else Set(CoordinatorStateKey)

  var shards = Set.empty[String]
  var terminating = false
  var getShardHomeRequests: Set[(ActorRef, GetShardHome)] = Set.empty

  if (rememberEntities)
    replicator ! Subscribe(AllShardsKey, self)

  node.subscribe(self, ClusterEvent.InitialStateAsEvents, ClusterShuttingDown.getClass)

  // get state from ddata replicator, repeat until GetSuccess
  getCoordinatorState()
  getAllShards()

  override def receive: Receive = waitingForState(allKeys)

  // This state will drop all other messages since they will be retried
  def waitingForState(remainingKeys: Set[Key[ReplicatedData]]): Receive =
    ({
      case g @ GetSuccess(CoordinatorStateKey, _) =>
        state = g.get(CoordinatorStateKey).value.withRememberEntities(settings.rememberEntities)
        log.debug("Received initial coordinator state [{}]", state)
        val newRemainingKeys = remainingKeys - CoordinatorStateKey
        if (newRemainingKeys.isEmpty)
          becomeWaitingForStateInitialized()
        else
          context.become(waitingForState(newRemainingKeys))

      case GetFailure(CoordinatorStateKey, _) =>
        log.error(
          "The ShardCoordinator was unable to get an initial state within 'waiting-for-state-timeout': {} millis (retrying). Has ClusterSharding been started on all nodes?",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        getCoordinatorState()

      case NotFound(CoordinatorStateKey, _) =>
        val newRemainingKeys = remainingKeys - CoordinatorStateKey
        if (newRemainingKeys.isEmpty)
          becomeWaitingForStateInitialized()
        else
          context.become(waitingForState(newRemainingKeys))

      case g @ GetSuccess(AllShardsKey, _) =>
        shards = g.get(AllShardsKey).elements
        val newUnallocatedShards = state.unallocatedShards.union(shards.diff(state.shards.keySet))
        state = state.copy(unallocatedShards = newUnallocatedShards)
        val newRemainingKeys = remainingKeys - AllShardsKey
        if (newRemainingKeys.isEmpty)
          becomeWaitingForStateInitialized()
        else
          context.become(waitingForState(newRemainingKeys))

      case GetFailure(AllShardsKey, _) =>
        log.error(
          "The ShardCoordinator was unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
          readMajority.timeout.toMillis)
        // repeat until GetSuccess
        getAllShards()

      case NotFound(AllShardsKey, _) =>
        val newRemainingKeys = remainingKeys - AllShardsKey
        if (newRemainingKeys.isEmpty)
          becomeWaitingForStateInitialized()
        else
          context.become(waitingForState(newRemainingKeys))

      case ShardCoordinator.Internal.Terminate =>
        log.debug("Received termination message while waiting for state")
        context.stop(self)
    }: Receive).orElse[Any, Unit](receiveTerminated)

  private def becomeWaitingForStateInitialized(): Unit = {
    if (state.isEmpty) {
      // empty state, activate immediately
      activate()
    } else {
      context.become(waitingForStateInitialized)
      // note that watchStateActors may call update
      watchStateActors()
    }
  }

  // this state will stash all messages until it receives StateInitialized,
  // which was scheduled by previous watchStateActors
  def waitingForStateInitialized: Receive = {
    case StateInitialized =>
      unstashGetShardHomeRequests()
      unstashAll()
      stateInitialized()
      activate()

    case g: GetShardHome =>
      stashGetShardHomeRequest(sender(), g)

    case ShardCoordinator.Internal.Terminate =>
      log.debug("Received termination message while waiting for state initialized")
      context.stop(self)

    case _ => stash()
  }

  // this state will stash all messages until it receives UpdateSuccess
  def waitingForUpdate[E <: DomainEvent](
      evt: E,
      afterUpdateCallback: E => Unit,
      remainingKeys: Set[Key[ReplicatedData]]): Receive = {
    case UpdateSuccess(CoordinatorStateKey, Some(`evt`)) =>
      log.debug("The coordinator state was successfully updated with {}", evt)
      val newRemainingKeys = remainingKeys - CoordinatorStateKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(CoordinatorStateKey, Some(`evt`)) =>
      log.error(
        "The ShardCoordinator was unable to update a distributed state within 'updating-state-timeout': {} millis ({}). " +
        "Perhaps the ShardRegion has not started on all active nodes yet? event={}",
        writeMajority.timeout.toMillis,
        if (terminating) "terminating" else "retrying",
        evt)
      if (terminating) {
        context.stop(self)
      } else {
        // repeat until UpdateSuccess
        sendCoordinatorStateUpdate(evt)
      }

    case UpdateSuccess(AllShardsKey, Some(newShard: String)) =>
      log.debug("The coordinator shards state was successfully updated with {}", newShard)
      val newRemainingKeys = remainingKeys - AllShardsKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(AllShardsKey, Some(newShard: String)) =>
      log.error(
        "The ShardCoordinator was unable to update shards distributed state within 'updating-state-timeout': {} millis ({}), event={}",
        writeMajority.timeout.toMillis,
        if (terminating) "terminating" else "retrying",
        evt)
      if (terminating) {
        context.stop(self)
      } else {
        // repeat until UpdateSuccess
        sendAllShardsUpdate(newShard)
      }

    case ModifyFailure(key, error, cause, _) =>
      log.error(
        cause,
        "The ShardCoordinator was unable to update a distributed state {} with error {} and event {}. {}",
        key,
        error,
        evt,
        if (terminating) "Coordinator will be terminated due to Terminate message received"
        else "Coordinator will be restarted")
      if (terminating) {
        context.stop(self)
      } else {
        throw cause
      }

    case g @ GetShardHome(shard) =>
      if (!handleGetShardHome(shard))
        stashGetShardHomeRequest(sender(), g) // must wait for update that is in progress

    case ShardCoordinator.Internal.Terminate =>
      log.debug("Received termination message while waiting for update")
      terminating = true
      stash()

    case _ => stash()
  }

  private def unbecomeAfterUpdate[E <: DomainEvent](evt: E, afterUpdateCallback: E => Unit): Unit = {
    context.unbecome()
    afterUpdateCallback(evt)
    log.debug("New coordinator state after [{}]: [{}]", evt, state)
    unstashGetShardHomeRequests()
    unstashAll()
  }

  private def stashGetShardHomeRequest(sender: ActorRef, request: GetShardHome): Unit = {
    log.debug(
      "GetShardHome [{}] request from [{}] stashed, because waiting for initial state or update of state. " +
      "It will be handled afterwards.",
      request.shard,
      sender)
    getShardHomeRequests += (sender -> request)
  }

  private def unstashGetShardHomeRequests(): Unit = {
    getShardHomeRequests.foreach {
      case (originalSender, request) => self.tell(request, sender = originalSender)
    }
    getShardHomeRequests = Set.empty
  }

  def activate() = {
    context.become(active)
    log.info("ShardCoordinator was moved to the active state {}", state)
  }

  override def active: Receive =
    if (rememberEntities) {
      ({
        case chg @ Changed(AllShardsKey) =>
          shards = chg.get(AllShardsKey).elements
      }: Receive).orElse[Any, Unit](super.active)
    } else
      super.active

  def update[E <: DomainEvent](evt: E)(f: E => Unit): Unit = {
    sendCoordinatorStateUpdate(evt)
    evt match {
      case s: ShardHomeAllocated if rememberEntities && !shards(s.shard) =>
        sendAllShardsUpdate(s.shard)
        context.become(waitingForUpdate(evt, f, allKeys), discardOld = false)
      case _ =>
        // no update of shards, already known
        context.become(waitingForUpdate(evt, f, Set(CoordinatorStateKey)), discardOld = false)
    }

  }

  def getCoordinatorState(): Unit = {
    replicator ! Get(CoordinatorStateKey, readMajority)
  }

  def getAllShards(): Unit = {
    if (rememberEntities)
      replicator ! Get(AllShardsKey, readMajority)
  }

  def sendCoordinatorStateUpdate(evt: DomainEvent) = {
    val s = state.updated(evt)
    log.debug("Publishing new coordinator state [{}]", state)
    replicator ! Update(CoordinatorStateKey, LWWRegister(selfUniqueAddress, initEmptyState), writeMajority, Some(evt)) {
      reg =>
        reg.withValueOf(s)
    }
  }

  def sendAllShardsUpdate(newShard: String) = {
    replicator ! Update(AllShardsKey, GSet.empty[String], writeMajority, Some(newShard))(_ + newShard)
  }

}
*/
