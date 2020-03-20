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
import akka.actor.Terminated
import akka.actor.Timers
import akka.annotation.InternalStableApi
import akka.cluster.Cluster
import akka.cluster.sharding.internal.DDataRememberEntities
import akka.cluster.sharding.internal.EntityRecoveryStrategy
import akka.cluster.sharding.internal.EventSourcedRememberEntities
import akka.cluster.sharding.internal.EventSourcedRememberEntitiesStore
import akka.cluster.sharding.internal.NoOpStore
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntityStarter
import akka.coordination.lease.scaladsl.Lease
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.dispatch.ExecutionContexts
import akka.pattern.pipe
import akka.util.MessageBufferMap
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

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Shard]] actor.
   * If `settings.rememberEntities` is enabled the `PersistentShard`
   * subclass is used, otherwise `Shard`.
   */
  def props(
      typeName: String,
      shardId: ShardRegion.ShardId,
      entityProps: String => Props,
      settings: ClusterShardingSettings,
      extractEntityId: ShardRegion.ExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId,
      handOffStopMessage: Any,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(
      new Shard(
        typeName,
        shardId,
        entityProps,
        settings,
        replicator,
        extractEntityId,
        extractShardId,
        handOffStopMessage,
        majorityMinCap)).withDeploy(Deploy.local)

  case object PassivateIdleTick extends NoSerializationVerificationNeeded

  private final case class RememberedEntityIds(ids: Set[EntityId])
  private final case class RememberEntityStoreCrashed(store: ActorRef)

}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
// FIXME changes to constructor will break telemetry
@InternalStableApi
private[akka] final class Shard(
    typeName: String,
    shardId: ShardRegion.ShardId,
    entityProps: String => Props,
    settings: ClusterShardingSettings,
    replicator: ActorRef,
    extractEntityId: ShardRegion.ExtractEntityId,
    @unused extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging
    with Stash
    with Timers {

  import Shard._
  import ShardCoordinator.Internal.HandOff
  import ShardCoordinator.Internal.ShardStopped
  import ShardRegion.EntityId
  import ShardRegion.Msg
  import ShardRegion.Passivate
  import ShardRegion.ShardInitialized
  import ShardRegion.handOffStopperProps
  import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
  import akka.cluster.sharding.ShardRegion.ShardRegionCommand
  import settings.tuningParameters._

  private val rememberEntitiesStore: RememberEntitiesShardStore =
    if (settings.rememberEntities)
      settings.stateStoreMode match {
        case ClusterShardingSettings.StateStoreModeDData =>
          // FIXME share a single instance across the region
          new DDataRememberEntities(context.system, typeName, settings, replicator, majorityMinCap)
        case ClusterShardingSettings.StateStoreModePersistence =>
          val store = context.actorOf(EventSourcedRememberEntitiesStore.props(typeName, shardId, settings))
          context.watchWith(store, RememberEntityStoreCrashed(store))
          new EventSourcedRememberEntities(store)
      } else NoOpStore

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

  // FIXME do we really need to repeat the entities three times here?
  private var entityIds: Set[EntityId] = Set.empty
  private var idByRef = Map.empty[ActorRef, EntityId]
  private var refById = Map.empty[EntityId, ActorRef]
  // toggles when shard actor is waiting for some async op
  private var waiting = true
  private var lastMessageTimestamp = Map.empty[EntityId, Long]
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

  override def preStart(): Unit = {
    acquireLeaseIfNeeded()
  }

  /**
   * Will call onLeaseAcquired when completed, also when lease isn't used
   */
  def acquireLeaseIfNeeded(): Unit = {
    lease match {
      case Some(l) =>
        tryGetLease(l)
        context.become(awaitingLease())
      case None =>
        onLeaseAcquired()
    }
  }

  // Override to execute logic once the lease has been acquired
  // Will be called on the actor thread
  def onLeaseAcquired(): Unit = {
    log.debug("Lease acquired")
    waitingForInitialEntities()
  }

  private def tryGetLease(l: Lease): Unit = {
    log.info("Acquiring lease {}", l.settings)
    pipe(l.acquire(reason => self ! LeaseLost(reason)).map(r => LeaseAcquireResult(r, None)).recover {
      case t => LeaseAcquireResult(acquired = false, Some(t))
    }).to(self)
  }

  def waitingForInitialEntities(): Unit = {
    def loadingEntityIdsFailed(ex: Throwable): Unit =
      throw new RuntimeException("Failed to load initial entity ids from remember entities store", ex)

    val futureEntityIds = rememberEntitiesStore.getEntities(shardId)

    futureEntityIds.value match {
      case Some(Success(entityIds)) => gotEntityIds(entityIds)
      case Some(Failure(ex))        => loadingEntityIdsFailed(ex)
      case None =>
        log.debug("Waiting for load of entity ids using [{}] to complete", rememberEntitiesStore)
        futureEntityIds.map(RememberedEntityIds)(ExecutionContexts.parasitic).pipeTo(self)
        context.become {
          case RememberedEntityIds(entityIds) =>
            gotEntityIds(entityIds)
          case Failure(ex) =>
            loadingEntityIdsFailed(ex)
          case _ =>
            stash()
        }
    }
  }

  def gotEntityIds(ids: Set[EntityId]): Unit = {
    if (ids.nonEmpty) {
      restartRememberedEntities(ids)
    } else {
      log.debug("Shard initialized")
    }
    waiting = false
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

  // copied from ddata shard, looks fishy to me
  override protected[akka] def aroundReceive(rcv: Receive, msg: Any): Unit = {
    super.aroundReceive(rcv, msg)
    if (!waiting)
      unstash() // unstash one message
  }

  def receive: Receive = receiveCommand

  // Don't send back ShardInitialized so that messages are buffered in the ShardRegion
  // while awaiting the lease
  private def awaitingLease(): Receive = {
    case LeaseAcquireResult(true, _) =>
      log.debug("Acquired lease")
      onLeaseAcquired()
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
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender())
  }

  def waitForAsyncWrite(entityId: EntityId, done: Future[Done])(whenDone: EntityId => Unit): Unit = {
    done.value match {
      case Some(Success(_))  => whenDone(entityId)
      case Some(Failure(ex)) => throw ex
      case None =>
        done.pipeTo(self)
        context.become {
          // FIXME do we need a more specific done message?
          case Done =>
            whenDone(entityId)
            waiting = false
            context.become(receiveCommand)

          // FIXME do we need a more specific failure message?
          case Failure(ex) =>
            // FIXME would be nice with if it was a start or a stop as well
            throw new RuntimeException(s"Remember entities write for [$entityId] failed", ex)

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
          case msg if extractEntityId.isDefinedAt(msg) => deliverOrBufferMessage(msg, entityId)
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
    log.error("Shard type [{}] id [{}] lease lost. Reason: {}", typeName, shardId, msg.reason)
    // Stop entities ASAP rather than send termination message
    context.stop(self)
  }

  private def receiveShardCommand(msg: ShardCommand): Unit = msg match {
    // those are only used with remembering entities
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
      waitForAsyncWrite(start.entityId, rememberEntitiesStore.addEntity(shardId, start.entityId)) { id =>
        getOrCreateEntity(id)
        sendMsgBuffer(id)
        requester ! ShardRegion.StartEntityAck(id, shardId)
      }
    }
  }

  private def receiveStartEntityAck(ack: ShardRegion.StartEntityAck): Unit = {
    if (ack.shardId != shardId && entityIds(ack.entityId)) {
      log.debug("Entity [{}] previously owned by shard [{}] started in shard [{}]", ack.entityId, shardId, ack.shardId)

      waitForAsyncWrite(ack.entityId, rememberEntitiesStore.removeEntity(shardId, ack.entityId)) { id =>
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
      //Note; because we're not persisting the EntityStopped, we don't need
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
        // FIXME wait for completion or not?
        waitForAsyncWrite(id, rememberEntitiesStore.removeEntity(shardId, id))(passivateCompleted)
      }
    }

    passivating = passivating - ref
  }

  private def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    idByRef.get(entity) match {
      case Some(id) =>
        if (!messageBuffers.contains(id)) {
          passivating = passivating + entity
          messageBuffers.add(id)
          entity ! stopMessage
        } else {
          log.debug("Passivation already in progress for {}. Not sending stopMessage back to entity.", entity)
        }
      case None => log.debug("Unknown entity {}. Not sending stopMessage back to entity.", entity)
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
      waitForAsyncWrite(entityId, rememberEntitiesStore.addEntity(shardId, entityId))(sendMsgBuffer)
    } else {
      log.debug("Entity stopped after passivation [{}]", entityId)
      messageBuffers.remove(entityId)
    }

  }

  // After entity started
  def sendMsgBuffer(entityId: EntityId): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrEmpty(entityId)
    messageBuffers.remove(entityId)

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", entityId, messages.size)
      getOrCreateEntity(entityId)
      //Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages.foreach {
        case (msg, snd) => deliverMessage(msg, snd)
      }
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      payload match {
        case start: ShardRegion.StartEntity =>
          // in case it was wrapped, used in Typed
          receiveStartEntity(start)
        case _ =>
          if (messageBuffers.contains(id))
            appendToMessageBuffer(id, msg, snd)
          else
            deliverTo(id, msg, payload, snd)
      }
    }
  }

  // FIXME this vs deliverMessage
  /**
   * If the message is for the same entity as we are waiting for the update it will be added to
   * its messageBuffer, which will be sent after the update has completed.
   *
   * If the message is for another entity that is already started (and not in progress of passivating)
   * it will be delivered immediately.
   *
   * Otherwise it will be stashed, and processed after the update has been completed.
   */
  private def deliverOrBufferMessage(msg: Any, entityIdWaitingForWrite: EntityId): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      payload match {
        case _: ShardRegion.StartEntity =>
          // in case it was wrapped, used in Typed
          stash()
        case _ =>
          if (id == entityIdWaitingForWrite) {
            appendToMessageBuffer(id, msg, sender())
          } else {
            val name = URLEncoder.encode(id, "utf-8")
            // messageBuffers.contains(id) when passivation is in progress
            if (!messageBuffers.contains(id) && context.child(name).nonEmpty) {
              deliverTo(id, msg, payload, sender())
            } else {
              // FIXME curious log message, stashing "to"?
              log.debug("Stashing to [{}] while waiting for remember entities update", id)
              stash()
            }
          }
      }
    }
  }

  def appendToMessageBuffer(id: EntityId, msg: Any, snd: ActorRef): Unit = {
    if (messageBuffers.totalSize >= bufferSize) {
      log.debug("Buffer is full, dropping message for entity [{}]", id)
      context.system.deadLetters ! msg
    } else {
      log.debug("Message for entity [{}] buffered", id)
      messageBuffers.append(id, msg, snd)
    }
  }

  def deliverTo(id: EntityId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    if (rememberEntitiesStore == NoOpStore) {
      touchLastMessageTimestamp(id)
      getOrCreateEntity(id).tell(payload, snd)
    } else {
      // remember entities has more expensive tricky logic looking at children. Why you ask. Not sure.
      val name = URLEncoder.encode(id, "utf-8")
      context.child(name) match {
        case Some(actor) =>
          touchLastMessageTimestamp(id)
          actor.tell(payload, snd)
        case None =>
          if (entityIds.contains(id)) {
            // this may happen when entity is stopped without passivation
            require(!messageBuffers.contains(id), s"Message buffers contains id [$id].")
            getOrCreateEntity(id).tell(payload, snd)
          } else {
            //Note; we only do this if remembering, otherwise the buffer is an overhead
            messageBuffers.append(id, msg, snd)
            waitForAsyncWrite(id, rememberEntitiesStore.addEntity(shardId, id))(sendMsgBuffer)
          }
      }
    }
  }

  @InternalStableApi
  def getOrCreateEntity(id: EntityId): ActorRef = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(child) => child
      case None =>
        log.debug("Starting entity [{}] in shard [{}]", id, shardId)
        val a = context.watch(context.actorOf(entityProps(id), name))
        idByRef = idByRef.updated(a, id)
        refById = refById.updated(id, a)
        entityIds = entityIds + id
        touchLastMessageTimestamp(id)
        a
    }
  }

  def rememberEntityStoreCrashed(msg: RememberEntityStoreCrashed): Unit = {
    throw new RuntimeException(s"Remember entities store [${msg.store}] crashed")
  }

  override def postStop(): Unit = {
    passivateIdleTask.foreach(_.cancel())
  }
}
