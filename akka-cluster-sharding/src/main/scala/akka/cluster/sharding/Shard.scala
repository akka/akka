/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.net.URLEncoder
import java.util

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.DeadLetterSuppression
import akka.actor.Deploy
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Terminated
import akka.actor.Timers
import akka.annotation.InternalStableApi
import akka.cluster.Cluster
import akka.cluster.sharding.internal.EntityRecoveryStrategy
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntitiesShardStore.GetEntities
import akka.cluster.sharding.internal.RememberEntitiesProvider
import akka.cluster.sharding.internal.RememberEntityStarter
import akka.coordination.lease.scaladsl.Lease
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.pattern.pipe
import akka.util.MessageBufferMap
import akka.util.OptionVal
import akka.util.PrettyDuration._
import akka.util.unused

import scala.collection.immutable.Set
import scala.concurrent.duration._

/**
 * INTERNAL API
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] object Shard {
  import ShardRegion.EntityId

  /**
   * A Shard command
   */
  sealed trait RememberEntityCommand

  /**
   * When remembering entities and the entity stops without issuing a `Passivate`, we
   * restart it after a back off using this message.
   */
  final case class RestartTerminatedEntity(entity: EntityId) extends RememberEntityCommand

  /**
   * When initialising a shard with remember entities enabled the following message is used
   * to restart batches of entity actors at a time.
   */
  final case class RestartRememberedEntities(entity: Set[EntityId]) extends RememberEntityCommand

  /**
   * A query for information about the shard
   */
  sealed trait ShardQuery

  @SerialVersionUID(1L) case object GetCurrentShardState extends ShardQuery

  @SerialVersionUID(1L) final case class CurrentShardState(shardId: ShardRegion.ShardId, entityIds: Set[EntityId])

  @SerialVersionUID(1L) case object GetShardStats extends ShardQuery with ClusterShardingSerializable

  @SerialVersionUID(1L) final case class ShardStats(shardId: ShardRegion.ShardId, entityCount: Int)
      extends ClusterShardingSerializable

  final case class LeaseAcquireResult(acquired: Boolean, reason: Option[Throwable]) extends DeadLetterSuppression
  final case class LeaseLost(reason: Option[Throwable]) extends DeadLetterSuppression

  case object LeaseRetry extends DeadLetterSuppression
  private val LeaseRetryTimer = "lease-retry"

  def props(
      typeName: String,
      shardId: ShardRegion.ShardId,
      entityProps: String => Props,
      settings: ClusterShardingSettings,
      extractEntityId: ShardRegion.ExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId,
      handOffStopMessage: Any,
      rememberEntitiesProvider: Option[RememberEntitiesProvider]): Props =
    Props(
      new Shard(
        typeName,
        shardId,
        entityProps,
        settings,
        extractEntityId,
        extractShardId,
        handOffStopMessage,
        rememberEntitiesProvider)).withDeploy(Deploy.local)

  case object PassivateIdleTick extends NoSerializationVerificationNeeded

  private final case class RememberedEntityIds(ids: Set[EntityId])
  private final case class RememberEntityStoreCrashed(store: ActorRef)

  private val RememberEntityTimeoutKey = "RememberEntityTimeout"
  final case class RememberEntityTimeout(operation: RememberEntitiesShardStore.Command)

  // FIXME Leaving this on while we are working on the remember entities refactor
  // should it go in settings perhaps, useful for tricky sharding bugs?
  final val VerboseDebug = true

  /**
   * State machine for an entity:
   * {{{
   *              Entity id remembered on shard start     +-------------------------+                 restart (via region)
   *                   +--------------------------------->| RememberedButNotCreated |------------------------------+
   *                   |                                  +-------------------------+                              |
   *                   |                                           |                                               |
   *                   |                                           | early message for entity                      |
   *                   |                                           v                                               |
   *                   |   Remember entities entity start +-------------------+   start stored and entity started  |
   *                   |        +-----------------------> | RememberingStart  |-------------+                      v
   * No state for id   |        |                         +-------------------+             |               +------------+
   *      +---+        |        |                                                           +-------------> |   Active   |
   *      |   |--------|--------|------------------------------------------------------------               +------------+
   *      +---+                 |   Non remember entities entity start                                             |
   *        ^                   |                                                                                  |
   *        |                   |                                                                                  |
   *        |                   |      restart after backoff                             entity terminated         |
   *        |                   |      or message for entity    +-------------------+    without passivation       |    passivation initiated     +-------------+
   *        |                   +<------------------------------| WaitingForRestart |<-----------------+-----------+----------------------------> | Passivating |
   *        |                   |                               +-------------------+                  |                                          +-------------+
   *        |                   |                                                                      |                                                |
   *        |                   |                                                                      |                              entity terminated +--------------+
   *        |                   |                                                                      |                                                v              |
   *        |                   |                  There were buffered messages for entity             |                                      +-------------------+    |
   *        |                   +<---------------------------------------------------------------------+                                      |   RememberingStop |    |
   *        |                                                                                                                                 +-------------------+    |
   *        |                                                                                                                                           |              |
   *        |                                                                                                                                           |              |
   *        +-------------------------------------------------------------------------------------------------------------------------------------------+<--------------
   *                       stop stored/passivation complete
   * }}}
   **/
  sealed trait EntityState {
    def transition(newState: EntityState): EntityState
  }

  /**
   * Empty state rather than using optionals,
   * is never really kept track of but used to verify state transitions
   * and as return value instead of null
   */
  case object NoState extends EntityState {
    override def transition(newState: EntityState): EntityState = newState match {
      case RememberedButNotCreated       => RememberedButNotCreated
      case remembering: RememberingStart => remembering
      case active: Active                => active
      case _                             => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }

  /**
   * In this state we know the entity has been stored in the remember sore but
   * it hasn't been created yet. E.g. on restart when first getting all the
   * remembered entity ids
   */
  // FIXME: since remember entities on start has a hop via region this could be a (small) resource leak
  // if the shard extractor has changed, the entities will stay in the map forever as RememberedButNotCreated
  // do we really need to track it?
  case object RememberedButNotCreated extends EntityState {
    override def transition(newState: EntityState): EntityState = newState match {
      case NoState        => RememberedButNotCreated
      case active: Active => active
      case _              => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }

  /**
   * When remember entities is enabled an entity is in this state while
   * its existence is being recorded in the remember entities store, or while the stop is queued up
   * to be stored in the next batch.
   */
  final case class RememberingStart(ackTo: Option[ActorRef]) extends EntityState {
    override def transition(newState: EntityState): EntityState = newState match {
      case active: Active => active
      case r: RememberingStart =>
        ackTo match {
          case None => r
          case Some(_) =>
            r // FIXME is this really fine, replace ackTo with another one or None
        }
      case _ => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }
  private val RememberingStartNoAck = RememberingStart(None)

  sealed trait StopReason
  final case object PassivationComplete extends StopReason
  final case object StartedElsewhere extends StopReason

  /**
   * When remember entities is enabled an entity is in this state while
   * its stop is being recorded in the remember entities store, or while the stop is queued up
   * to be stored in the next batch.
   */
  final case class RememberingStop(stopReason: StopReason) extends EntityState {
    override def transition(newState: EntityState): EntityState = newState match {
      case NoState => NoState
      case _       => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }

  sealed trait WithRef extends EntityState {
    def ref: ActorRef
  }
  final case class Active(ref: ActorRef) extends WithRef {
    override def transition(newState: EntityState): EntityState = newState match {
      case passivating: Passivating => passivating
      case WaitingForRestart        => WaitingForRestart
      case _                        => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }
  final case class Passivating(ref: ActorRef) extends WithRef {
    override def transition(newState: EntityState): EntityState = newState match {
      case r: RememberingStop => r
      case NoState            => NoState
      case _                  => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }

  case object WaitingForRestart extends EntityState {
    override def transition(newState: EntityState): EntityState = newState match {
      case remembering: RememberingStart => remembering
      case active: Active                => active
      case _                             => throw new IllegalArgumentException(s"Transition from $this to $newState not allowed")
    }
  }

  final class Entities(log: LoggingAdapter, rememberingEntities: Boolean) {
    private val entities: java.util.Map[EntityId, EntityState] = new util.HashMap[EntityId, EntityState]()
    // needed to look up entity by reg when a Passivating is received
    private val byRef = new util.HashMap[ActorRef, EntityId]()
    private val remembering = new util.HashSet[EntityId]()

    def alreadyRemembered(set: Set[EntityId]): Unit = {
      set.foreach { entityId =>
        val state = entityState(entityId).transition(RememberedButNotCreated)
        entities.put(entityId, state)
      }
    }
    def rememberingStart(entityId: EntityId, ackTo: Option[ActorRef]): Unit = {
      // FIXME we queue these without bounds, is that ok?
      val newState: RememberingStart = ackTo match {
        case None => RememberingStartNoAck // small optimization avoiding alloc for regular start
        case some => RememberingStart(some)
      }
      val state = entityState(entityId).transition(newState)
      entities.put(entityId, state)
      if (rememberingEntities)
        remembering.add(entityId)
    }
    def rememberingStop(entityId: EntityId, reason: StopReason): Unit = {
      val state = entityState(entityId)
      removeRefIfThereIsOne(state)
      entities.put(entityId, state.transition(RememberingStop(reason)))
      if (rememberingEntities)
        remembering.add(entityId)
    }
    def waitingForRestart(id: EntityId): Unit = {
      val state = entities.get(id) match {
        case wr: WithRef =>
          byRef.remove(wr.ref)
          wr
        case null  => NoState
        case other => other
      }
      entities.put(id, state.transition(WaitingForRestart))
    }
    def removeEntity(entityId: EntityId): Unit = {
      val state = entityState(entityId)
      // just verify transition
      state.transition(NoState)
      removeRefIfThereIsOne(state)
      entities.remove(entityId)
      if (rememberingEntities)
        remembering.remove(entityId)
    }
    def addEntity(entityId: EntityId, ref: ActorRef): Unit = {
      val state = entityState(entityId).transition(Active(ref))
      entities.put(entityId, state)
      byRef.put(ref, entityId)
      if (rememberingEntities)
        remembering.remove(entityId)
    }
    def entity(entityId: EntityId): OptionVal[ActorRef] = entities.get(entityId) match {
      case wr: WithRef => OptionVal.Some(wr.ref)
      case _           => OptionVal.None

    }
    def entityState(id: EntityId): EntityState = {
      OptionVal(entities.get(id)).getOrElse(NoState)
    }

    def entityId(ref: ActorRef): OptionVal[EntityId] = OptionVal(byRef.get(ref))

    def isPassivating(id: EntityId): Boolean = {
      entities.get(id) match {
        case _: Passivating => true
        case _              => false
      }
    }
    def entityPassivating(entityId: EntityId): Unit = {
      if (VerboseDebug) log.debug("[{}] passivating", entityId)
      entities.get(entityId) match {
        case wf: WithRef =>
          val state = entityState(entityId).transition(Passivating(wf.ref))
          entities.put(entityId, state)
        case other =>
          throw new IllegalStateException(
            s"Tried to passivate entity without an actor ref $entityId. Current state $other")
      }
    }
    private def removeRefIfThereIsOne(state: EntityState): Unit = {
      state match {
        case wr: WithRef =>
          byRef.remove(wr.ref)
        case _ =>
      }
    }

    import akka.util.ccompat.JavaConverters._
    // only called once during handoff
    def activeEntities(): Set[ActorRef] = byRef.keySet.asScala.toSet

    // only called for getting shard stats
    def activeEntityIds(): Set[EntityId] = byRef.values.asScala.toSet

    /**
     * @return (remembering start, remembering stop)
     */
    def pendingRememberEntities(): (Map[EntityId, RememberingStart], Set[EntityId]) = {
      if (remembering.isEmpty) {
        (Map.empty, Set.empty)
      } else {
        val starts = Map.newBuilder[EntityId, RememberingStart]
        val stops = Set.newBuilder[EntityId]
        remembering.forEach(entityId =>
          entityState(entityId) match {
            case r: RememberingStart => starts += (entityId -> r)
            case _: RememberingStop  => stops += entityId
            case wat                 => throw new IllegalStateException(s"$entityId was in the remembering set but has state $wat")
          })
        (starts.result(), stops.result())
      }
    }

    def pendingRememberedEntitiesExist(): Boolean = !remembering.isEmpty

    def entityIdExists(id: EntityId): Boolean = entities.get(id) != null
    def size: Int = entities.size

    override def toString: EntityId = entities.toString
  }
}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
// FIXME I broke bin comp here
@InternalStableApi
private[akka] class Shard(
    typeName: String,
    shardId: ShardRegion.ShardId,
    entityProps: String => Props,
    settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    @unused extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any,
    rememberEntitiesProvider: Option[RememberEntitiesProvider])
    extends Actor
    with ActorLogging
    with Stash
    with Timers {

  import Shard._
  import ShardCoordinator.Internal.HandOff
  import ShardCoordinator.Internal.ShardStopped
  import ShardRegion.EntityId
  import ShardRegion.Passivate
  import ShardRegion.ShardInitialized
  import ShardRegion.handOffStopperProps

  import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage

  private val rememberEntitiesStore: Option[ActorRef] =
    rememberEntitiesProvider.map { provider =>
      val store = context.actorOf(provider.shardStoreProps(shardId).withDeploy(Deploy.local), "RememberEntitiesStore")
      context.watchWith(store, RememberEntityStoreCrashed(store))
      store
    }

  private val flightRecorder = ShardingFlightRecorder(context.system)

  private val entities = new Entities(log, settings.rememberEntities)

  private var lastMessageTimestamp = Map.empty[EntityId, Long]

  // Messages are buffered while an entity is passivating or waiting for a response
  // for that entity from the remember store
  private val messageBuffers = new MessageBufferMap[EntityId]

  private var handOffStopper: Option[ActorRef] = None

  import context.dispatcher
  private val passivateIdleTask = if (settings.shouldPassivateIdleEntities) {
    val idleInterval = settings.passivateIdleEntityAfter / 2
    Some(context.system.scheduler.scheduleWithFixedDelay(idleInterval, idleInterval, self, PassivateIdleTick))
  } else {
    None
  }

  private val lease = settings.leaseSettings.map(
    ls =>
      LeaseProvider(context.system).getLease(
        s"${context.system.name}-shard-$typeName-$shardId",
        ls.leaseImplementation,
        Cluster(context.system).selfAddress.hostPort))

  private val leaseRetryInterval = settings.leaseSettings match {
    case Some(l) => l.leaseRetryInterval
    case None    => 5.seconds // not used
  }

  def receive: Receive = {
    case _ => throw new IllegalStateException("Default receive never expected to actually be used")
  }

  override def preStart(): Unit = {
    acquireLeaseIfNeeded()
  }

  // ===== lease handling initialization =====
  def acquireLeaseIfNeeded(): Unit = {
    lease match {
      case Some(l) =>
        tryGetLease(l)
        context.become(awaitingLease())
      case None =>
        tryLoadRememberedEntities()
    }
  }

  // Don't send back ShardInitialized so that messages are buffered in the ShardRegion
  // while awaiting the lease
  private def awaitingLease(): Receive = {
    case LeaseAcquireResult(true, _) =>
      log.debug("Lease acquired")
      tryLoadRememberedEntities()
    case LeaseAcquireResult(false, None) =>
      log.error(
        "Failed to get lease for shard type [{}] id [{}]. Retry in {}",
        typeName,
        shardId,
        leaseRetryInterval.pretty)
      timers.startSingleTimer(LeaseRetryTimer, LeaseRetry, leaseRetryInterval)
    case LeaseAcquireResult(false, Some(t)) =>
      log.error(
        t,
        "Failed to get lease for shard type [{}] id [{}]. Retry in {}",
        typeName,
        shardId,
        leaseRetryInterval)
      timers.startSingleTimer(LeaseRetryTimer, LeaseRetry, leaseRetryInterval)
    case LeaseRetry =>
      tryGetLease(lease.get)
    case ll: LeaseLost =>
      receiveLeaseLost(ll)
    case _ =>
      stash()
  }

  private def tryGetLease(l: Lease): Unit = {
    log.info("Acquiring lease {}", l.settings)
    pipe(l.acquire(reason => self ! LeaseLost(reason)).map(r => LeaseAcquireResult(r, None)).recover {
      case t => LeaseAcquireResult(acquired = false, Some(t))
    }).to(self)
  }

  // ===== remember entities initialization =====
  def tryLoadRememberedEntities(): Unit = {
    rememberEntitiesStore match {
      case Some(store) =>
        log.debug("Waiting for load of entity ids using [{}] to complete", store)
        store ! RememberEntitiesShardStore.GetEntities
        timers.startSingleTimer(
          RememberEntityTimeoutKey,
          RememberEntityTimeout(GetEntities),
          settings.tuningParameters.waitingForStateTimeout)
        context.become(awaitingRememberedEntities())
      case None =>
        onEntitiesRemembered(Set.empty)
    }
  }

  def awaitingRememberedEntities(): Receive = {
    case RememberEntitiesShardStore.RememberedEntities(entityIds) =>
      timers.cancel(RememberEntityTimeoutKey)
      onEntitiesRemembered(entityIds)
    case RememberEntityTimeout(GetEntities) =>
      loadingEntityIdsFailed()
    case msg =>
      if (VerboseDebug)
        log.debug("Got msg of type [{}] from [{}] while waiting for remember entitites", msg.getClass, sender())
      stash()
  }

  def loadingEntityIdsFailed(): Unit = {
    log.error(
      "Failed to load initial entity ids from remember entities store within [{}], stopping shard for backoff and restart",
      settings.tuningParameters.waitingForStateTimeout.pretty)
    // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
    context.stop(self)
  }

  def onEntitiesRemembered(ids: Set[EntityId]): Unit = {
    log.debug("Shard initialized")
    if (ids.nonEmpty) {
      entities.alreadyRemembered(ids)
      restartRememberedEntities(ids)
    }
    context.parent ! ShardInitialized(shardId)
    context.become(idle)
    unstashAll()
  }

  def restartRememberedEntities(ids: Set[EntityId]): Unit = {
    log.debug(
      "Shard starting [{}] remembered entities using strategy [{}]",
      ids.size,
      rememberedEntitiesRecoveryStrategy)
    // FIXME Separation of concerns: shouldn't this future juggling be part of the RememberEntityStarter actor instead?
    rememberedEntitiesRecoveryStrategy.recoverEntities(ids).foreach { scheduledRecovery =>
      scheduledRecovery
        .filter(_.nonEmpty)(ExecutionContexts.parasitic)
        .map(RestartRememberedEntities)(ExecutionContexts.parasitic)
        .pipeTo(self)
    }
  }

  def restartEntities(ids: Set[EntityId]): Unit = {
    log.debug("Restarting set of [{}] entities", ids.size)
    context.actorOf(RememberEntityStarter.props(context.parent, ids, settings, sender()))
  }

  // ===== shard up and running =====

  // when not remembering entities, we stay in this state all the time
  def idle: Receive = {
    case Terminated(ref)                         => receiveTerminated(ref)
    case msg: CoordinatorMessage                 => receiveCoordinatorMessage(msg)
    case msg: RememberEntityCommand              => receiveRememberEntityCommand(msg)
    case msg: ShardRegion.StartEntity            => startEntity(msg.entityId, Some(sender()))
    case msg: ShardRegion.StartEntityAck         => receiveStartEntityAck(msg)
    case Passivate(stopMessage)                  => passivate(sender(), stopMessage)
    case msg: ShardQuery                         => receiveShardQuery(msg)
    case PassivateIdleTick                       => passivateIdleEntities()
    case msg: LeaseLost                          => receiveLeaseLost(msg)
    case msg: RememberEntityStoreCrashed         => rememberEntityStoreCrashed(msg)
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender())
  }

  def rememberUpdate(add: Set[EntityId] = Set.empty, remove: Set[EntityId] = Set.empty): Unit = {
    rememberEntitiesStore match {
      case None =>
        onUpdateDone(add, remove)
      case Some(store) =>
        sendToRememberStore(store, storingStarts = add, storingStops = remove)
    }
  }

  def sendToRememberStore(store: ActorRef, storingStarts: Set[EntityId], storingStops: Set[EntityId]): Unit = {
    if (VerboseDebug)
      log.debug(
        "Remember update [{}] and stops [{}] triggered",
        storingStarts.mkString(", "),
        storingStops.mkString(", "))

    storingStarts.foreach { entityId =>
      flightRecorder.rememberEntityAdd(entityId)
    }
    storingStops.foreach { id =>
      flightRecorder.rememberEntityRemove(id)
    }
    val startTimeNanos = System.nanoTime()
    val update = RememberEntitiesShardStore.Update(started = storingStarts, stopped = storingStops)
    store ! update
    timers.startSingleTimer(
      RememberEntityTimeoutKey,
      RememberEntityTimeout(update),
      // FIXME this timeout needs to match the timeout used in the ddata shard write since that tries 3 times
      // and this could always fail before ddata store completes retrying writes
      settings.tuningParameters.updatingStateTimeout)

    context.become(waitingForRememberEntitiesStore(update, startTimeNanos))
  }

  private def waitingForRememberEntitiesStore(
      update: RememberEntitiesShardStore.Update,
      startTimeNanos: Long): Receive = {
    // none of the current impls will send back a partial update, yet!
    case RememberEntitiesShardStore.UpdateDone(storedStarts, storedStops) =>
      val duration = System.nanoTime() - startTimeNanos
      if (VerboseDebug)
        log.debug(
          "Update done for ids, started [{}], stopped [{}]. Duration {} ms",
          storedStarts.mkString(", "),
          storedStops.mkString(", "),
          duration.nanos.toMillis)
      flightRecorder.rememberEntityOperation(duration)
      timers.cancel(RememberEntityTimeoutKey)
      onUpdateDone(storedStarts, storedStops)

    case RememberEntityTimeout(`update`) =>
      log.error("Remember entity store did not respond, crashing shard")
      throw new RuntimeException(
        s"Async write timed out after ${settings.tuningParameters.updatingStateTimeout.pretty}")
    case ShardRegion.StartEntity(entityId) =>
      if (!entities.entityIdExists(entityId)) {
        if (VerboseDebug)
          log.debug(
            "StartEntity([{}]) from [{}] while a write already in progress. Marking as pending",
            entityId,
            sender())
        entities.rememberingStart(entityId, ackTo = Some(sender()))
      } else {
        // it's already running, ack immediately
        sender() ! ShardRegion.StartEntityAck(entityId, shardId)
      }

    case Terminated(ref)       => receiveTerminated(ref)
    case _: CoordinatorMessage => stash()
    case RestartTerminatedEntity(entityId) =>
      entities.entityState(entityId) match {
        case WaitingForRestart =>
          if (VerboseDebug)
            log.debug("Restarting terminated entity [{}]", entityId)
          getOrCreateEntity(entityId)
        case other =>
          throw new IllegalStateException(
            s"Got RestartTerminatedEntity for [$entityId] but it's not waiting to be restarted. Actual state [$other]")
      }
    case RestartRememberedEntities(entities) => restartEntities(entities)
    case l: LeaseLost                        => receiveLeaseLost(l)
    case ShardRegion.StartEntityAck(entityId, _) =>
      if (update.started.contains(entityId)) {
        // currently in progress of starting, so we'll need to stop it when that is done
        entities.rememberingStop(entityId, StartedElsewhere)
      } else {
        entities.entityState(entityId) match {
          case _: RememberingStart =>
            // queued up for batched start, let's just not start it
            entities.removeEntity(entityId)
          case _: Active =>
            // add to stop batch
            entities.rememberingStop(entityId, StartedElsewhere)
          case _ =>
            // not sure for other states, so deal with it later
            stash()
        }
      }
    case ShardRegion.Passivate(stopMessage) =>
      if (VerboseDebug)
        log.debug(
          "Passivation of [{}] arrived while updating",
          entities.entityId(sender()).getOrElse(s"Unknown actor ${sender()}"))
      passivate(sender(), stopMessage)
    case msg: ShardQuery                 => receiveShardQuery(msg)
    case PassivateIdleTick               => stash()
    case msg: RememberEntityStoreCrashed => rememberEntityStoreCrashed(msg)
    case msg if extractEntityId.isDefinedAt(msg) =>
      deliverMessage(msg, sender())
    case msg =>
      // shouldn't be any other message types, but just in case
      log.warning(
        "Stashing unexpected message [{}] while waiting for remember entities update of starts [{}], stops [{}]",
        msg.getClass,
        update.started.mkString(", "),
        update.stopped.mkString(", "))
      stash()

  }

  def onUpdateDone(starts: Set[EntityId], stops: Set[EntityId]): Unit = {
    // entities can contain both ids from start/stops and pending ones, so we need
    // to mark the completed ones as complete to get the set of pending ones
    starts.foreach { entityId =>
      val stateBeforeStart = entities.entityState(entityId)
      // this will start the entity and transition the entity state in sessions to active
      getOrCreateEntity(entityId)
      sendMsgBuffer(entityId)
      stateBeforeStart match {
        case RememberingStart(Some(ackTo)) => ackTo ! ShardRegion.StartEntityAck(entityId, shardId)
        case _                             =>
      }
      touchLastMessageTimestamp(entityId)
    }
    stops.foreach { entityId =>
      entities.entityState(entityId) match {
        case RememberingStop(PassivationComplete) =>
          // this updates entity state
          passivateCompleted(entityId)
        case RememberingStop(StartedElsewhere) =>
          // Drop buffered messages if any (to not cause re-ordering)
          messageBuffers.remove(entityId)
          entities.removeEntity(entityId)
        case state =>
          throw new IllegalStateException(
            s"Unexpected state [$state] when storing stop completed for entity id [$entityId]")
      }
    }

    val (pendingStarts, pendingStops) = entities.pendingRememberEntities()
    if (pendingStarts.isEmpty && pendingStops.isEmpty) {
      if (VerboseDebug) log.debug("Update complete, no pending updates, going to idle")
      unstashAll()
      context.become(idle)
    } else {
      // Note: no unstashing as long as we are batching, is that a problem?
      val pendingStartIds = pendingStarts.keySet
      if (VerboseDebug)
        log.debug(
          "Update complete, pending updates, doing another write. Starts [{}], stops [{}]",
          pendingStartIds.mkString(", "),
          pendingStops.mkString(", "))
      rememberUpdate(pendingStartIds, pendingStops)
    }
  }

  def receiveLeaseLost(msg: LeaseLost): Unit = {
    // The shard region will re-create this when it receives a message for this shard
    log.error(
      "Shard type [{}] id [{}] lease lost, stopping shard and killing [{}] entities.{}",
      typeName,
      shardId,
      entities.size,
      msg.reason match {
        case Some(reason) => s" Reason for losing lease: $reason"
        case None         => ""
      })
    // Stop entities ASAP rather than send termination message
    context.stop(self)
  }

  private def receiveRememberEntityCommand(msg: RememberEntityCommand): Unit = msg match {
    case RestartTerminatedEntity(entityId) =>
      entities.entityState(entityId) match {
        case WaitingForRestart =>
          if (VerboseDebug) log.debug("Restarting entity unexpectedly terminated entity [{}]", entityId)
          getOrCreateEntity(entityId)
        case Active(_) =>
          // it up could already have been started, that's fine
          if (VerboseDebug) log.debug("Got RestartTerminatedEntity for [{}] but it is already running")
        case other =>
          throw new IllegalStateException(
            s"Unexpected state for [$entityId] when getting RestartTerminatedEntity: [$other]")
      }

    case RestartRememberedEntities(ids) => restartEntities(ids)
  }

  // this could be because of a start message or due to a new message for the entity
  // if it is a start entity then start entity ack is sent after it is created
  private def startEntity(entityId: EntityId, ackTo: Option[ActorRef]): Unit = {
    entities.entityState(entityId) match {
      case Active(_) =>
        log.debug("Request to start entity [{}] (Already started)", entityId)
        touchLastMessageTimestamp(entityId)
        ackTo.foreach(_ ! ShardRegion.StartEntityAck(entityId, shardId))
      case _: RememberingStart =>
        // FIXME safe to replace ackTo?
        entities.rememberingStart(entityId, ackTo)
      case RememberedButNotCreated =>
        // already remembered, just start it - this will be the normal path for initially remembered entities
        log.debug("Request to start (already remembered) entity [{}]", entityId)
        getOrCreateEntity(entityId)
        touchLastMessageTimestamp(entityId)
        ackTo.foreach(_ ! ShardRegion.StartEntityAck(entityId, shardId))
      case NoState =>
        log.debug("Request to start entity [{}] and ack to [{}]", entityId, ackTo)
        entities.rememberingStart(entityId, ackTo)
        rememberUpdate(add = Set(entityId))
      case other =>
        // FIXME what do we do here?
        throw new IllegalStateException(s"Unhandled state when wanting to start $entityId: $other")
    }
  }

  private def receiveStartEntityAck(ack: ShardRegion.StartEntityAck): Unit = {
    if (ack.shardId != shardId && entities.entityIdExists(ack.entityId)) {
      log.debug("Entity [{}] previously owned by shard [{}] started in shard [{}]", ack.entityId, shardId, ack.shardId)

      entities.rememberingStop(ack.entityId, StartedElsewhere)
      rememberUpdate(remove = Set(ack.entityId))
    }
  }

  private def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HandOff(`shardId`) => handOff(sender())
    case HandOff(shard)     => log.warning("Shard [{}] can not hand off for another Shard [{}]", shardId, shard)
    case _                  => unhandled(msg)
  }

  def receiveShardQuery(msg: ShardQuery): Unit = msg match {
    case GetCurrentShardState =>
      if (VerboseDebug)
        log.debug("GetCurrentShardState, full state: [{}], active: [{}]", entities, entities.activeEntityIds())
      sender() ! CurrentShardState(shardId, entities.activeEntityIds())
    case GetShardStats => sender() ! ShardStats(shardId, entities.size)
  }

  private def handOff(replyTo: ActorRef): Unit = handOffStopper match {
    case Some(_) => log.warning("HandOff shard [{}] received during existing handOff", shardId)
    case None =>
      log.debug("HandOff shard [{}]", shardId)

      // does conversion so only do once
      val activeEntities = entities.activeEntities()
      if (activeEntities.nonEmpty) {
        val entityHandOffTimeout = (settings.tuningParameters.handOffTimeout - 5.seconds).max(1.seconds)
        log.debug("Starting HandOffStopper for shard [{}] to terminate [{}] entities.", shardId, activeEntities.size)
        activeEntities.foreach(context.unwatch(_))
        handOffStopper = Some(
          context.watch(context.actorOf(
            handOffStopperProps(shardId, replyTo, activeEntities, handOffStopMessage, entityHandOffTimeout))))

        //During hand off we only care about watching for termination of the hand off stopper
        context.become {
          case Terminated(ref) => receiveTerminated(ref)
        }
      } else {
        replyTo ! ShardStopped(shardId)
        context.stop(self)
      }
  }

  private def receiveTerminated(ref: ActorRef): Unit = {
    if (handOffStopper.contains(ref))
      context.stop(self)
    else {
      // workaround for watchWith not working with stash #29101
      entities.entityId(ref) match {
        case OptionVal.Some(id) => entityTerminated(id)
        case _                  =>
      }
    }
  }

  @InternalStableApi
  def entityTerminated(entityId: EntityId): Unit = {
    import settings.tuningParameters._
    if (passivateIdleTask.isDefined) {
      lastMessageTimestamp -= entityId
    }
    entities.entityState(entityId) match {
      case RememberingStop(_) =>
        if (VerboseDebug)
          log.debug("Stop of [{}] arrived, already is among the pending stops", entityId)
      case Active(_) =>
        log.debug("Entity [{}] stopped without passivating, will restart after backoff", entityId)
        entities.waitingForRestart(entityId)
        val msg = RestartTerminatedEntity(entityId)
        timers.startSingleTimer(msg, msg, entityRestartBackoff)

      case Passivating(_) =>
        if (entities.pendingRememberedEntitiesExist()) {
          // will go in next batch update
          if (VerboseDebug)
            log.debug(
              "Stop of [{}] after passivating, arrived while updating, adding it to batch of pending stops",
              entityId)
          entities.rememberingStop(entityId, PassivationComplete)
        } else {
          entities.rememberingStop(entityId, PassivationComplete)
          rememberUpdate(remove = Set(entityId))
        }
      case unexpected =>
        val ref = entities.entity(entityId)
        log.warning(
          "Got a terminated for [{}], entityId [{}] which is in unexpected state [{}]",
          ref,
          entityId,
          unexpected)
    }
  }

  private def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    entities.entityId(entity) match {
      case OptionVal.Some(id) =>
        if (entities.isPassivating(id)) {
          log.debug("Passivation already in progress for [{}]. Not sending stopMessage back to entity", id)
        } else if (messageBuffers.getOrEmpty(id).nonEmpty) {
          log.debug("Passivation when there are buffered messages for [{}], ignoring", id)
          // FIXME should we buffer the stop message then?
        } else {
          if (VerboseDebug)
            log.debug("Passivation started for [{}]", id)
          entities.entityPassivating(id)
          entity ! stopMessage
          flightRecorder.entityPassivate(id)
        }
      case OptionVal.None =>
        log.debug("Unknown entity passivating [{}]. Not sending stopMessage back to entity", entity)
    }
  }

  def touchLastMessageTimestamp(id: EntityId): Unit = {
    if (passivateIdleTask.isDefined) {
      lastMessageTimestamp = lastMessageTimestamp.updated(id, System.nanoTime())
    }
  }

  private def passivateIdleEntities(): Unit = {
    val deadline = System.nanoTime() - settings.passivateIdleEntityAfter.toNanos
    val refsToPassivate = lastMessageTimestamp.collect {
      case (entityId, lastMessageTimestamp) if lastMessageTimestamp < deadline && entities.entity(entityId).isDefined =>
        entities.entity(entityId).get
    }
    if (refsToPassivate.nonEmpty) {
      log.debug("Passivating [{}] idle entities", refsToPassivate.size)
      refsToPassivate.foreach(passivate(_, handOffStopMessage))
    }
  }

  // After entity stopped
  def passivateCompleted(entityId: EntityId): Unit = {
    val hasBufferedMessages = messageBuffers.getOrEmpty(entityId).nonEmpty
    entities.removeEntity(entityId)
    if (hasBufferedMessages) {
      log.debug("Entity stopped after passivation [{}], but will be started again due to buffered messages", entityId)
      flightRecorder.entityPassivateRestart(entityId)
      // trigger start or batch in case we're already writing to the remember store
      entities.rememberingStart(entityId, None)
      if (!entities.pendingRememberedEntitiesExist()) rememberUpdate(Set(entityId))
    } else {
      log.debug("Entity stopped after passivation [{}]", entityId)
    }
  }

  private def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (entityId, payload) = extractEntityId(msg)
    if (entityId == null || entityId == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      payload match {
        case start: ShardRegion.StartEntity =>
          // Handling StartEntity both here and in the receives allows for sending it both as is and in an envelope
          // to be extracted by the entity id extractor.

          // we can only start a new entity if we are not currently waiting for another write
          if (entities.pendingRememberedEntitiesExist()) {
            if (VerboseDebug)
              log.debug("StartEntity({}) from [{}], adding to batch", start.entityId, snd)
            entities.rememberingStart(entityId, ackTo = Some(snd))
          } else {
            if (VerboseDebug)
              log.debug("StartEntity({}) from [{}], starting", start.entityId, snd)
            startEntity(start.entityId, Some(sender()))
          }
        case _ =>
          entities.entityState(entityId) match {
            case Active(ref) =>
              if (VerboseDebug)
                log.debug("Delivering message of type [{}] to [{}]", payload.getClass, entityId)
              touchLastMessageTimestamp(entityId)
              ref.tell(payload, snd)
            case RememberingStart(_) | RememberingStop(_) | Passivating(_) =>
              appendToMessageBuffer(entityId, msg, snd)
            case state @ (WaitingForRestart | RememberedButNotCreated) =>
              if (VerboseDebug)
                log.debug(
                  "Delivering message of type [{}] to [{}] (starting because [{}])",
                  payload.getClass,
                  entityId,
                  state)
              val actor = getOrCreateEntity(entityId)
              touchLastMessageTimestamp(entityId)
              actor.tell(payload, snd)
            case NoState =>
              if (entities.pendingRememberedEntitiesExist()) {
                // No actor running and write in progress for some other entity id (can only happen with remember entities enabled)
                if (VerboseDebug)
                  log.debug(
                    "Buffer message [{}] to [{}] (which is not started) because of write in progress for [{}]",
                    payload.getClass,
                    entityId,
                    entities.pendingRememberEntities())
                appendToMessageBuffer(entityId, msg, snd)
                entities.rememberingStart(entityId, ackTo = None)
              } else {
                // No actor running and no write in progress, start actor and deliver message when started
                if (VerboseDebug)
                  log.debug("Buffering message [{}] to [{}] and starting actor", payload.getClass, entityId)
                appendToMessageBuffer(entityId, msg, snd)
                entities.rememberingStart(entityId, ackTo = None)
                rememberUpdate(add = Set(entityId))
              }

          }
      }
    }
  }

  @InternalStableApi
  def getOrCreateEntity(id: EntityId): ActorRef = {
    entities.entity(id) match {
      case OptionVal.Some(child) => child
      case OptionVal.None =>
        val name = URLEncoder.encode(id, "utf-8")
        val a = context.actorOf(entityProps(id), name)
        context.watch(a)
        log.debug("Started entity [{}] with entity id [{}] in shard [{}]", a, id, shardId)
        entities.addEntity(id, a)
        touchLastMessageTimestamp(id)
        a
    }
  }

  // ===== buffering while busy saving a start or stop when remembering entities =====
  def appendToMessageBuffer(id: EntityId, msg: Any, snd: ActorRef): Unit = {
    if (messageBuffers.totalSize >= settings.tuningParameters.bufferSize) {
      if (log.isDebugEnabled)
        log.debug("Buffer is full, dropping message of type [{}] for entity [{}]", msg.getClass.getName, id)
      context.system.deadLetters ! msg
    } else {
      if (log.isDebugEnabled)
        log.debug("Message of type [{}] for entity [{}] buffered", msg.getClass.getName, id)
      messageBuffers.append(id, msg, snd)
    }
  }

  // After entity started
  def sendMsgBuffer(entityId: EntityId): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrEmpty(entityId)
    messageBuffers.remove(entityId)

    if (messages.nonEmpty) {
      getOrCreateEntity(entityId)
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", entityId, messages.size)
      // Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages.foreach {
        case (msg, snd) => deliverMessage(msg, snd)
      }
      touchLastMessageTimestamp(entityId)
    }
  }

  def dropBufferFor(entityId: EntityId): Unit = {
    if (log.isDebugEnabled) {
      val messages = messageBuffers.getOrEmpty(entityId)
      if (messages.nonEmpty)
        log.debug("Dropping [{}] buffered messages", entityId, messages.size)
    }
    messageBuffers.remove(entityId)
  }

  private def rememberEntityStoreCrashed(msg: RememberEntityStoreCrashed): Unit = {
    throw new RuntimeException(s"Remember entities store [${msg.store}] crashed")
  }

  override def postStop(): Unit = {
    passivateIdleTask.foreach(_.cancel())
    log.debug("Shard [{}] shutting down", shardId)
  }

  private def rememberedEntitiesRecoveryStrategy: EntityRecoveryStrategy = {
    import settings.tuningParameters._
    entityRecoveryStrategy match {
      case "all" => EntityRecoveryStrategy.allStrategy()
      case "constant" =>
        EntityRecoveryStrategy.constantStrategy(
          context.system,
          entityRecoveryConstantRateStrategyFrequency,
          entityRecoveryConstantRateStrategyNumberOfEntities)
    }
  }
}
