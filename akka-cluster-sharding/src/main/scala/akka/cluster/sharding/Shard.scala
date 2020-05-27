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
  final case class RestartEntity(entity: EntityId) extends RememberEntityCommand

  /**
   * When initialising a shard with remember entities enabled the following message is used
   * to restart batches of entity actors at a time.
   */
  final case class RestartEntities(entity: Set[EntityId]) extends RememberEntityCommand

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
  private final case object AsyncWriteDone

  private val RememberEntityTimeoutKey = "RememberEntityTimeout"
  case class RememberEntityTimeout(operation: RememberEntitiesShardStore.Command)

  // FIXME Leaving this on while we are working on the remember entities refactor
  // should it go in settings perhaps, useful for tricky sharding bugs?
  final val VerboseDebug = true

  sealed trait EntityState

  /**
   * In this state we know the entity has been stored in the remember sore but
   * it hasn't been created yet. E.g. on restart when first getting all the
   * remembered entity ids
   */
  case object RememberedButNotCreated extends EntityState

  /**
   * When remember entities is enabled an entity is in this state while
   * its existence is being recorded in the remember entities store.
   */
  case object Remembering extends EntityState
  sealed trait WithRef extends EntityState {
    def ref: ActorRef
  }
  final case class Active(ref: ActorRef) extends WithRef
  final case class Passivating(ref: ActorRef) extends WithRef
  case object WaitingForRestart extends EntityState

  /**
   * Entity has been stopped. If the entity was pasivating it'll be removed from state
   * once the stop has been recorded.
   */
  case object Stopped extends EntityState

  final case class EntityTerminated(id: EntityId, ref: ActorRef)

  final class Entities(log: LoggingAdapter) {

    private val entities: java.util.Map[EntityId, EntityState] = new util.HashMap[EntityId, EntityState]()
    // needed to look up entity by reg when a Passivating is received
    private val byRef = new util.HashMap[ActorRef, EntityId]()

    def alreadyRemembered(set: Set[EntityId]): Unit = {
      set.foreach { id =>
        entities.put(id, RememberedButNotCreated)
      }
    }
    def remembering(entity: EntityId): Unit = {
      entities.put(entity, Remembering)
    }
    def terminated(ref: ActorRef): Unit = {
      val id = byRef.get(ref)
      entities.put(id, Stopped)
      byRef.remove(ref)
    }
    def waitingForRestart(id: EntityId): Unit = {
      entities.get(id) match {
        case wr: WithRef =>
          byRef.remove(wr.ref)
        case _ =>
      }
      entities.put(id, WaitingForRestart)
    }
    def removeEntity(entityId: EntityId): Unit = {
      entities.get(entityId) match {
        case wr: WithRef =>
          byRef.remove(wr.ref)
        case _ =>
      }
      entities.remove(entityId)
    }
    def addEntity(entityId: EntityId, ref: ActorRef): Unit = {
      entities.put(entityId, Active(ref))
      byRef.put(ref, entityId)
    }
    def entity(entityId: EntityId): OptionVal[ActorRef] = entities.get(entityId) match {
      case wr: WithRef => OptionVal.Some(wr.ref)
      case _           => OptionVal.None

    }
    def entityState(id: EntityId): OptionVal[EntityState] = {
      OptionVal(entities.get(id))
    }

    def entityId(ref: ActorRef): OptionVal[EntityId] = OptionVal(byRef.get(ref))

    def isPassivating(id: EntityId): Boolean = {
      entities.get(id) match {
        case _: Passivating => true
        case _              => false
      }
    }
    def entityPassivating(entityId: EntityId): Unit = {
      if (VerboseDebug) log.debug("{} passivating, all entities: {}", entityId, entities)
      entities.get(entityId) match {
        case wf: WithRef =>
          entities.put(entityId, Passivating(wf.ref))
        case other =>
          throw new IllegalStateException(
            s"Tried to passivate entity without an actor ref $entityId. Current state $other")
      }
    }

    import akka.util.ccompat.JavaConverters._
    // only called once during handoff
    def activeEntities(): Set[ActorRef] = byRef.keySet.asScala.toSet

    // only called for getting shard stats
    def activeEntityIds(): Set[EntityId] = byRef.values.asScala.toSet

    def entityIdExists(id: EntityId): Boolean = entities.get(id) != null
    def size: Int = entities.size
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
  import settings.tuningParameters._

  import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
  import akka.cluster.sharding.ShardRegion.ShardRegionCommand

  private val rememberEntitiesStore: Option[ActorRef] =
    rememberEntitiesProvider.map { provider =>
      val store = context.actorOf(provider.shardStoreProps(shardId).withDeploy(Deploy.local), "RememberEntitiesStore")
      context.watchWith(store, RememberEntityStoreCrashed(store))
      store
    }

  private val rememberedEntitiesRecoveryStrategy: EntityRecoveryStrategy = {
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

  private val entities = new Entities(log)

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
    } else {}
    context.parent ! ShardInitialized(shardId)
    context.become(idle)
    unstashAll()
  }

  def restartRememberedEntities(ids: Set[EntityId]): Unit = {
    log.debug(
      "Shard starting [{}] remembered entities using strategy [{}]",
      ids.size,
      rememberedEntitiesRecoveryStrategy)
    rememberedEntitiesRecoveryStrategy.recoverEntities(ids).foreach { scheduledRecovery =>
      import context.dispatcher
      scheduledRecovery.filter(_.nonEmpty).map(RestartEntities).pipeTo(self)
    }
  }

  def restartEntities(ids: Set[EntityId]): Unit = {
    log.debug("Restarting set of [{}] entities", ids.size)
    context.actorOf(RememberEntityStarter.props(context.parent, ids, settings, sender()))
  }

  // ===== shard up and running =====

  def idle: Receive = {
    case Terminated(ref)                         => receiveTerminated(ref)
    case EntityTerminated(id, ref)               => entityTerminated(ref, id)
    case msg: CoordinatorMessage                 => receiveCoordinatorMessage(msg)
    case msg: RememberEntityCommand              => receiveRememberEntityCommand(msg)
    case msg: ShardRegion.StartEntity            => startEntities(Map(msg.entityId -> Some(sender())))
    case msg: ShardRegion.StartEntityAck         => receiveStartEntityAck(msg)
    case msg: ShardRegionCommand                 => receiveShardRegionCommand(msg)
    case msg: ShardQuery                         => receiveShardQuery(msg)
    case PassivateIdleTick                       => passivateIdleEntities()
    case msg: LeaseLost                          => receiveLeaseLost(msg)
    case msg: RememberEntityStoreCrashed         => rememberEntityStoreCrashed(msg)
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender(), OptionVal.None)
  }

  def sendToRememberStore(entityId: EntityId, command: RememberEntitiesShardStore.Command)(
      whenDone: () => Unit): Unit = {
    sendToRememberStore(Set(entityId), command)(() => whenDone())
  }

  def sendToRememberStore(entityIds: Set[EntityId], command: RememberEntitiesShardStore.Command)(
      whenDone: () => Unit): Unit = {
    rememberEntitiesStore match {
      case None =>
        whenDone()

      case Some(store) =>
        if (VerboseDebug) log.debug("Update of [{}] [{}] triggered", entityIds.mkString(", "), command)

        entityIds.foreach(entities.remembering)
        store ! command
        timers.startSingleTimer(
          RememberEntityTimeoutKey,
          RememberEntityTimeout(command),
          // FIXME this timeout needs to match the timeout used in the ddata shard write since that tries 3 times
          // and this could always fail before ddata store completes retrying writes
          settings.tuningParameters.updatingStateTimeout)

        context.become(waitingForUpdate(Map.empty))

        def waitingForUpdate(pendingStarts: Map[EntityId, Option[ActorRef]]): Receive = {
          // none of the current impls will send back a partial update, yet!
          case RememberEntitiesShardStore.UpdateDone(ids) =>
            if (VerboseDebug) log.debug("Update done for ids [{}]", ids.mkString(", "))
            timers.cancel(RememberEntityTimeoutKey)
            whenDone()
            if (pendingStarts.isEmpty) {
              if (VerboseDebug) log.debug("No pending entities, going to idle")
              context.become(idle)
              unstashAll()
            } else {
              if (VerboseDebug)
                log.debug(
                  "New entities encountered while waiting starting those: [{}]",
                  pendingStarts.keys.mkString(", "))
              startEntities(pendingStarts)
            }
          case RememberEntityTimeout(`command`) =>
            throw new RuntimeException(
              s"Async write for entityIds $entityIds timed out after ${settings.tuningParameters.updatingStateTimeout.pretty}")
          case msg: ShardRegion.StartEntity =>
            if (VerboseDebug)
              log.debug(
                "Start entity while a write already in progress. Pending writes [{}]. Writes in progress [{}]",
                pendingStarts.keys.mkString(", "),
                entityIds.mkString(", "))
            if (!entities.entityIdExists(msg.entityId))
              context.become(waitingForUpdate(pendingStarts + (msg.entityId -> Some(sender()))))

          // below cases should handle same messages as in idle
          case _: Terminated                           => stash()
          case _: EntityTerminated                     => stash()
          case _: CoordinatorMessage                   => stash()
          case _: RememberEntityCommand                => stash()
          case _: ShardRegion.StartEntityAck           => stash()
          case _: ShardRegionCommand                   => stash()
          case msg: ShardQuery                         => receiveShardQuery(msg)
          case PassivateIdleTick                       => stash()
          case msg: LeaseLost                          => receiveLeaseLost(msg)
          case msg: RememberEntityStoreCrashed         => rememberEntityStoreCrashed(msg)
          case msg if extractEntityId.isDefinedAt(msg) =>
            // FIXME now the delivery logic is again spread out across two places, is this needed over what is in deliverMessage?
            val (id, _) = extractEntityId(msg)
            if (entities.entityIdExists(id)) {
              if (VerboseDebug) log.debug("Entity already known about. Try and deliver. [{}]", id)
              deliverMessage(msg, sender(), OptionVal.Some(entityIds))
            } else {
              if (VerboseDebug) log.debug("New entity, add it to batch of pending starts. [{}]", id)
              appendToMessageBuffer(id, msg, sender())
              context.become(waitingForUpdate(pendingStarts + (id -> None)))
            }
          case msg =>
            // shouldn't be any other message types, but just in case
            log.warning(
              "Stashing unexpected message [{}] while waiting for remember entities update of [{}]",
              msg.getClass,
              entityIds.mkString(", "))
            stash()
        }
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
    // these are only used with remembering entities upon start
    case RestartEntity(id) =>
      // starting because it was remembered as started on shard startup (note that a message starting
      // it up could already have arrived and in that case it will already be started)
      getOrCreateEntity(id)
    case RestartEntities(ids) => restartEntities(ids)
  }

  // this could be because of a start message or due to a new message for the entity
  // if it is a start entity then start entity ack is sent after it is created
  private def startEntities(entities: Map[EntityId, Option[ActorRef]]): Unit = {
    val alreadyStarted = entities.filterKeys(entity => this.entities.entityIdExists(entity))
    val needStarting = entities -- alreadyStarted.keySet
    if (log.isDebugEnabled) {
      log.debug(
        "Request to start entities. Already started [{}]. Need starting [{}]",
        alreadyStarted.keys.mkString(", "),
        needStarting.keys.mkString(", "))
    }
    alreadyStarted.foreach {
      case (entityId, requestor) =>
        getOrCreateEntity(entityId)
        touchLastMessageTimestamp(entityId)
        requestor.foreach(_ ! ShardRegion.StartEntityAck(entityId, shardId))
    }

    if (needStarting.nonEmpty) {
      sendToRememberStore(needStarting.keySet, RememberEntitiesShardStore.AddEntities(needStarting.keySet)) { () =>
        needStarting.foreach {
          case (entityId, requestor) =>
            getOrCreateEntity(entityId)
            sendMsgBuffer(entityId)
            requestor.foreach(_ ! ShardRegion.StartEntityAck(entityId, shardId))
        }
      }
    }
  }

  private def receiveStartEntityAck(ack: ShardRegion.StartEntityAck): Unit = {
    if (ack.shardId != shardId && entities.entityIdExists(ack.entityId)) {
      log.debug("Entity [{}] previously owned by shard [{}] started in shard [{}]", ack.entityId, shardId, ack.shardId)

      sendToRememberStore(ack.entityId, RememberEntitiesShardStore.RemoveEntity(ack.entityId)) { () =>
        entities.removeEntity(ack.entityId)
        messageBuffers.remove(ack.entityId)
      }
    }
  }

  private def receiveShardRegionCommand(msg: ShardRegionCommand): Unit = msg match {
    case Passivate(stopMessage) => passivate(sender(), stopMessage)
    case _                      => unhandled(msg)
  }

  private def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HandOff(`shardId`) => handOff(sender())
    case HandOff(shard)     => log.warning("Shard [{}] can not hand off for another Shard [{}]", shardId, shard)
    case _                  => unhandled(msg)
  }

  def receiveShardQuery(msg: ShardQuery): Unit = msg match {
    case GetCurrentShardState => sender() ! CurrentShardState(shardId, entities.activeEntityIds())
    case GetShardStats        => sender() ! ShardStats(shardId, entities.size)
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
        case OptionVal.Some(id) => entityTerminated(ref, id)
        case _                  =>
      }
    }
  }

  @InternalStableApi
  def entityTerminated(ref: ActorRef, id: EntityId): Unit = {
    import settings.tuningParameters._
    val isPassivating = entities.isPassivating(id)
    entities.terminated(ref)
    if (passivateIdleTask.isDefined) {
      lastMessageTimestamp -= id
    }
    if (messageBuffers.getOrEmpty(id).nonEmpty) {
      // Note; because we're not persisting the EntityStopped, we don't need
      // to persist the EntityStarted either.
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      // this will re-add the entity back
      sendMsgBuffer(id)
    } else {
      if (!isPassivating) {
        log.debug("Entity [{}] stopped without passivating, will restart after backoff", id)
        entities.waitingForRestart(id)
        import context.dispatcher
        context.system.scheduler.scheduleOnce(entityRestartBackoff, self, RestartEntity(id))
      } else {
        // FIXME optional wait for completion as optimization where stops are not critical
        sendToRememberStore(id, RememberEntitiesShardStore.RemoveEntity(id))(() => passivateCompleted(id))
      }
    }
  }

  private def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    entities.entityId(entity) match {
      case OptionVal.Some(id) =>
        if (!messageBuffers.contains(id)) {
          if (VerboseDebug)
            log.debug("Passivation started for {}", entity)

          entities.entityPassivating(id)
          messageBuffers.add(id)
          entity ! stopMessage
        } else {
          log.debug("Passivation already in progress for {}. Not sending stopMessage back to entity", entity)
        }
      case OptionVal.None => log.debug("Unknown entity {}. Not sending stopMessage back to entity", entity)
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
      sendToRememberStore(entityId, RememberEntitiesShardStore.AddEntities(Set(entityId))) { () =>
        sendMsgBuffer(entityId)
      }
    } else {
      log.debug("Entity stopped after passivation [{}]", entityId)
      dropBufferFor(entityId)
    }
  }

  /**
   * @param entityIdsWaitingForWrite ids for remembered entities that have a write in progress, if non empty messages for that id
   *                                will be buffered
   */
  private def deliverMessage(msg: Any, snd: ActorRef, entityIdsWaitingForWrite: OptionVal[Set[EntityId]]): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      payload match {
        case start: ShardRegion.StartEntity =>
          // we can only start a new entity if we are not currently waiting for another write
          if (entityIdsWaitingForWrite.isEmpty) startEntities(Map(start.entityId -> Some(sender())))
          // write in progress, see waitForAsyncWrite for unstash
          else stash()
        case _ =>
          entities.entityState(id) match {
            case OptionVal.Some(Remembering) =>
              appendToMessageBuffer(id, msg, snd)
            case OptionVal.Some(Passivating(_)) =>
              appendToMessageBuffer(id, msg, snd)
            case OptionVal.Some(Active(ref)) =>
              if (VerboseDebug) log.debug("Delivering message of type [{}] to [{}]", payload.getClass, id)
              touchLastMessageTimestamp(id)
              ref.tell(payload, snd)
            case OptionVal.Some(RememberedButNotCreated | WaitingForRestart) =>
              if (VerboseDebug)
                log.debug(
                  "Delivering message of type [{}] to [{}] (starting because known but not running)",
                  payload.getClass,
                  id)

              val actor = getOrCreateEntity(id)
              touchLastMessageTimestamp(id)
              actor.tell(payload, snd)
            case OptionVal.None if entityIdsWaitingForWrite.isEmpty =>
              // No actor running and no write in progress, start actor and deliver message when started
              if (VerboseDebug)
                log.debug("Buffering message [{}] to [{}] and starting actor", payload.getClass, id)
              appendToMessageBuffer(id, msg, snd)
              sendToRememberStore(id, RememberEntitiesShardStore.AddEntities(Set(id)))(() => sendMsgBuffer(id))
            case OptionVal.None =>
              // No actor running and write in progress for some other entity id, stash message for deliver when
              // unstash happens on async write complete
              if (VerboseDebug)
                log.debug(
                  "Buffer message [{}] to [{}] (which is not started) because of write in progress for [{}]",
                  payload.getClass,
                  id,
                  entityIdsWaitingForWrite.get)
              appendToMessageBuffer(id, msg, snd)
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
    if (messageBuffers.totalSize >= bufferSize) {
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
      //Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages.foreach {
        case (msg, snd) => deliverMessage(msg, snd, OptionVal.None)
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
  }
}
