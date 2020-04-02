/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.net.URLEncoder

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.DeadLetterSuppression
import akka.actor.Deploy
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Status.{ Failure => AkkaFailure }
import akka.actor.Terminated
import akka.actor.Timers
import akka.annotation.InternalStableApi
import akka.cluster.Cluster
import akka.cluster.sharding.internal.EntityRecoveryStrategy
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntitiesShardStoreProvider
import akka.cluster.sharding.internal.RememberEntityStarter
import akka.coordination.lease.scaladsl.Lease
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.dispatch.ExecutionContexts
import akka.pattern.pipe
import akka.util.MessageBufferMap
import akka.util.OptionVal
import akka.util.PrettyDuration._
import akka.util.unused

import scala.collection.immutable.Set
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

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
  sealed trait ShardCommand

  /**
   * When remembering entities and the entity stops without issuing a `Passivate`, we
   * restart it after a back off using this message.
   */
  final case class RestartEntity(entity: EntityId) extends ShardCommand

  /**
   * When initialising a shard with remember entities enabled the following message is used
   * to restart batches of entity actors at a time.
   */
  final case class RestartEntities(entity: Set[EntityId]) extends ShardCommand

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

  final case object LeaseRetry extends DeadLetterSuppression
  private val LeaseRetryTimer = "lease-retry"

  def props(
      typeName: String,
      shardId: ShardRegion.ShardId,
      entityProps: String => Props,
      settings: ClusterShardingSettings,
      extractEntityId: ShardRegion.ExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId,
      handOffStopMessage: Any,
      rememberEntitiesProvider: RememberEntitiesShardStoreProvider): Props =
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
  final case class RememberEntityStoreCrashed(store: ActorRef)
  private final case object AsyncWriteDone

  // FIXME Leaving this on while we are working on the remember entities refactor
  final val VerboseDebug = true
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
    rememberEntitiesProvider: RememberEntitiesShardStoreProvider)
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
  import akka.cluster.sharding.ShardRegion.ShardRegionCommand
  import settings.tuningParameters._

  private val rememberEntitiesStore: RememberEntitiesShardStore = rememberEntitiesProvider.createStoreForShard(shardId)

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

  // this will contain entity ids even if not yet started (or stopped without graceful stop)
  private var entityIds: Set[EntityId] = Set.empty

  private var idByRef = Map.empty[ActorRef, EntityId]
  private var refById = Map.empty[EntityId, ActorRef]

  private var lastMessageTimestamp = Map.empty[EntityId, Long]

  // that an entity is passivating it is added to the passivating set and its id is added to the message buffers
  // to buffer any messages coming in during passivation
  private var passivating = Set.empty[ActorRef]
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
    val futureEntityIds = rememberEntitiesStore.getEntities()

    futureEntityIds.value match {
      case Some(Success(entityIds)) => onEntitiesRemembered(entityIds)
      case Some(Failure(ex))        => loadingEntityIdsFailed(ex)
      case None =>
        log.debug("Waiting for load of entity ids using [{}] to complete", rememberEntitiesStore)
        futureEntityIds.map(RememberedEntityIds)(ExecutionContexts.parasitic).pipeTo(self)
        context.become(awaitingRememberedEntities())
    }
  }

  def awaitingRememberedEntities(): Receive = {
    case RememberedEntityIds(entityIds) =>
      onEntitiesRemembered(entityIds)
    case AkkaFailure(ex) =>
      loadingEntityIdsFailed(ex)
    case msg =>
      if (VerboseDebug)
        log.debug("Got msg of type [{}] from [{}] while waiting for remember entitites", msg.getClass, sender())
      stash()
  }

  def loadingEntityIdsFailed(ex: Throwable): Unit = {
    log.error(ex, "Failed to load initial entity ids from remember entities store")
    // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
    context.stop(self)
  }

  def onEntitiesRemembered(ids: Set[EntityId]): Unit = {
    log.debug("Shard initialized")
    if (ids.nonEmpty) {
      entityIds = ids
      restartRememberedEntities(ids)
    } else {}
    context.parent ! ShardInitialized(shardId)
    context.become(receiveCommand)
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

  def receiveCommand: Receive = {
    case Terminated(ref)                         => receiveTerminated(ref)
    case msg: CoordinatorMessage                 => receiveCoordinatorMessage(msg)
    case msg: ShardCommand                       => receiveShardCommand(msg)
    case msg: ShardRegion.StartEntity            => receiveStartEntity(msg)
    case msg: ShardRegion.StartEntityAck         => receiveStartEntityAck(msg)
    case msg: ShardRegionCommand                 => receiveShardRegionCommand(msg)
    case msg: ShardQuery                         => receiveShardQuery(msg)
    case PassivateIdleTick                       => passivateIdleEntities()
    case msg: LeaseLost                          => receiveLeaseLost(msg)
    case msg: RememberEntityStoreCrashed         => rememberEntityStoreCrashed(msg)
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender(), OptionVal.None)
  }

  def waitForAsyncWrite(operation: String, entityId: EntityId, done: Future[Done])(whenDone: EntityId => Unit): Unit = {
    if (VerboseDebug) log.debug("Waiting for async write of [{}] {}", entityId, operation)
    done.value match {
      case Some(Success(_))  => whenDone(entityId)
      case Some(Failure(ex)) => throw ex
      case None =>
        done.map(_ => AsyncWriteDone)(ExecutionContexts.parasitic).pipeTo(self)
        context.become {
          case AsyncWriteDone =>
            if (VerboseDebug) log.debug("Completed async write of [{}] {}", entityId, operation)
            whenDone(entityId)
            context.become(receiveCommand)
            unstashAll()
          case AkkaFailure(ex) =>
            throw new RuntimeException(s"Remember entities write of $operation for [$entityId] failed", ex)

          // below cases should handle same messages as in Shard.receiveCommand
          case _: Terminated                           => stash()
          case _: CoordinatorMessage                   => stash()
          case _: ShardCommand                         => stash()
          case _: ShardRegion.StartEntity              => stash()
          case _: ShardRegion.StartEntityAck           => stash()
          case _: ShardRegionCommand                   => stash()
          case msg: ShardQuery                         => receiveShardQuery(msg)
          case PassivateIdleTick                       => stash()
          case msg: LeaseLost                          => receiveLeaseLost(msg)
          case msg: RememberEntityStoreCrashed         => rememberEntityStoreCrashed(msg)
          case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender(), OptionVal.Some(entityId))
          case msg                                     =>
            // shouldn't be any other message types, but just in case
            log.debug(
              "Stashing unexpected message [{}] while waiting for remember entities update of {}",
              msg.getClass,
              entityId)
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
      entityIds.size,
      msg.reason match {
        case Some(reason) => s" Reason for losing lease: $reason"
        case None         => ""
      })
    // Stop entities ASAP rather than send termination message
    context.stop(self)
  }

  private def receiveShardCommand(msg: ShardCommand): Unit = msg match {
    // these are only used with remembering entities upon start
    case RestartEntity(id)    => getOrCreateEntity(id)
    case RestartEntities(ids) => restartEntities(ids)
  }

  private def receiveStartEntity(start: ShardRegion.StartEntity): Unit = {
    val requester = sender()
    log.debug("Got a request from [{}] to start entity [{}] in shard [{}]", requester, start.entityId, shardId)
    touchLastMessageTimestamp(start.entityId)

    if (entityIds(start.entityId)) {
      getOrCreateEntity(start.entityId)
      requester ! ShardRegion.StartEntityAck(start.entityId, shardId)
    } else {
      waitForAsyncWrite("add (start entity)", start.entityId, rememberEntitiesStore.addEntity(start.entityId)) { id =>
        getOrCreateEntity(id)
        sendMsgBuffer(id)
        requester ! ShardRegion.StartEntityAck(id, shardId)
      }
    }
  }

  private def receiveStartEntityAck(ack: ShardRegion.StartEntityAck): Unit = {
    if (ack.shardId != shardId && entityIds(ack.entityId)) {
      log.debug("Entity [{}] previously owned by shard [{}] started in shard [{}]", ack.entityId, shardId, ack.shardId)

      waitForAsyncWrite(
        "remove (started in other shard)",
        ack.entityId,
        rememberEntitiesStore.removeEntity(ack.entityId)) { id =>
        entityIds = entityIds - id
        messageBuffers.remove(id)
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
    case GetCurrentShardState => sender() ! CurrentShardState(shardId, refById.keySet)
    case GetShardStats        => sender() ! ShardStats(shardId, entityIds.size)
  }

  private def handOff(replyTo: ActorRef): Unit = handOffStopper match {
    case Some(_) => log.warning("HandOff shard [{}] received during existing handOff", shardId)
    case None =>
      log.debug("HandOff shard [{}]", shardId)

      if (idByRef.nonEmpty) {
        val entityHandOffTimeout = (settings.tuningParameters.handOffTimeout - 5.seconds).max(1.seconds)
        log.debug("Starting HandOffStopper for shard [{}] to terminate [{}] entities.", shardId, idByRef.keySet.size)
        handOffStopper = Some(
          context.watch(context.actorOf(
            handOffStopperProps(shardId, replyTo, idByRef.keySet, handOffStopMessage, entityHandOffTimeout))))

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
    else if (idByRef.contains(ref) && handOffStopper.isEmpty)
      entityTerminated(ref)
  }

  @InternalStableApi
  def entityTerminated(ref: ActorRef): Unit = {
    import settings.tuningParameters._
    val id = idByRef(ref)
    idByRef -= ref
    refById -= id
    if (passivateIdleTask.isDefined) {
      lastMessageTimestamp -= id
    }
    if (messageBuffers.getOrEmpty(id).nonEmpty) {
      // Note; because we're not persisting the EntityStopped, we don't need
      // to persist the EntityStarted either.
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      sendMsgBuffer(id)
    } else {
      if (!passivating.contains(ref)) {
        log.debug("Entity [{}] stopped without passivating, will restart after backoff", id)
        // note that it's not removed from state here, will be started again via RestartEntity
        import context.dispatcher
        context.system.scheduler.scheduleOnce(entityRestartBackoff, self, RestartEntity(id))
      } else {
        // FIXME optional wait for completion as optimization where stops are not critical
        waitForAsyncWrite("remove (terminated)", id, rememberEntitiesStore.removeEntity(id))(passivateCompleted)
      }
    }

    passivating = passivating - ref
  }

  private def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    idByRef.get(entity) match {
      case Some(id) =>
        if (!messageBuffers.contains(id)) {
          if (VerboseDebug)
            log.debug("Passivation started for {}", entity)
          passivating = passivating + entity
          messageBuffers.add(id)
          entity ! stopMessage
        } else {
          log.debug("Passivation already in progress for {}. Not sending stopMessage back to entity", entity)
        }
      case None => log.debug("Unknown entity {}. Not sending stopMessage back to entity", entity)
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
      case (entityId, lastMessageTimestamp) if lastMessageTimestamp < deadline => refById(entityId)
    }
    if (refsToPassivate.nonEmpty) {
      log.debug("Passivating [{}] idle entities", refsToPassivate.size)
      refsToPassivate.foreach(passivate(_, handOffStopMessage))
    }
  }

  // After entity stopped
  def passivateCompleted(entityId: EntityId): Unit = {
    val hasBufferedMessages = messageBuffers.getOrEmpty(entityId).nonEmpty
    entityIds = entityIds - entityId
    if (hasBufferedMessages) {
      log.debug("Entity stopped after passivation [{}], but will be started again due to buffered messages.", entityId)
      waitForAsyncWrite("remove (passivated)", entityId, rememberEntitiesStore.addEntity(entityId))(sendMsgBuffer)
    } else {
      log.debug("Entity stopped after passivation [{}]", entityId)
      dropBufferFor(entityId)
    }

  }

  /**
   * @param entityIdWaitingForWrite an id for an remember entity write in progress, if non empty messages for that id
   *                                will be buffered
   */
  private def deliverMessage(msg: Any, snd: ActorRef, entityIdWaitingForWrite: OptionVal[EntityId]): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      payload match {
        case start: ShardRegion.StartEntity =>
          // we can only start a new entity if we are not currently waiting for another write
          if (entityIdWaitingForWrite.isEmpty) receiveStartEntity(start)
          // write in progress, see waitForAsyncWrite for unstash
          else stash()
        case _ =>
          if (messageBuffers.contains(id) || entityIdWaitingForWrite.contains(id)) {
            // either:
            // 1. entity is passivating, buffer until passivation complete (id in message buffers)
            // 2. we are waiting for storing entity start or stop with remember entities to complete
            //    and want to buffer until write completes
            appendToMessageBuffer(id, msg, snd)
          } else {
            // With remember entities enabled we may be in the process of saving that we are starting up the entity
            // and in that case we need to buffer messages until that completes
            refById.get(id) match {
              case Some(actor) =>
                // not using remember entities or write is in progress for other entity and this entity is running already
                // go ahead and deliver
                if (VerboseDebug) log.debug("Delivering message of type [{}] to [{}]", payload.getClass, id)
                touchLastMessageTimestamp(id)
                actor.tell(payload, snd)
              case None =>
                if (entityIds.contains(id)) {
                  // No entity actor running but id is in set of entities, this can happen in two scenarios:
                  // 1. we are starting up and the entity id is remembered but not yet started
                  // 2. the entity is stopped but not passivated, and should be restarted

                  // FIXME won't this potentially lead to not remembered for case 2?
                  val actor = getOrCreateEntity(id)
                  touchLastMessageTimestamp(id)
                  actor.tell(payload, snd)

                } else {
                  if (entityIdWaitingForWrite.isEmpty) {
                    // No actor and id is unknown, start actor and deliver message when started
                    // Note; we only do this if remembering, otherwise the buffer is an overhead
                    appendToMessageBuffer(id, msg, snd)
                    waitForAsyncWrite("start (message)", id, rememberEntitiesStore.addEntity(id))(sendMsgBuffer)
                  } else {
                    // we'd need to start the entity but a start/stop write is already in progress
                    // see waitForAsyncWrite for unstash
                    stash()
                  }
                }
            }
          }
      }
    }
  }

  @InternalStableApi
  def getOrCreateEntity(id: EntityId): ActorRef = {
    refById.get(id) match {
      case Some(child) => child
      case None =>
        val name = URLEncoder.encode(id, "utf-8")
        val a = context.watch(context.actorOf(entityProps(id), name))
        log.debug("Started entity [{}] with entity id [{}] in shard [{}]", a, id, shardId)
        idByRef = idByRef.updated(a, id)
        refById = refById.updated(id, a)
        entityIds = entityIds + id
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

  def rememberEntityStoreCrashed(msg: RememberEntityStoreCrashed): Unit = {
    throw new RuntimeException(s"Remember entities store [${msg.store}] crashed")
  }

  override def postStop(): Unit = {
    passivateIdleTask.foreach(_.cancel())
    rememberEntitiesStore.stop()
  }
}
