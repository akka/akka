/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.net.URLEncoder

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.DeadLetterSuppression
import akka.actor.Deploy
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.Stash
import akka.actor.Terminated
import akka.actor.Timers
import akka.annotation.InternalStableApi
import akka.cluster.Cluster
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator._
import akka.cluster.ddata.SelfUniqueAddress
import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
import akka.cluster.sharding.ShardRegion.ShardInitialized
import akka.cluster.sharding.ShardRegion.ShardRegionCommand
import akka.coordination.lease.scaladsl.Lease
import akka.coordination.lease.scaladsl.LeaseProvider
import akka.pattern.pipe
import akka.persistence._
import akka.util.MessageBufferMap
import akka.util.PrettyDuration._
import akka.util.unused

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
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange extends ClusterShardingSerializable {
    val entityId: EntityId
  }

  /**
   * A query for information about the shard
   */
  sealed trait ShardQuery

  /**
   * `State` change for starting an entity in this `Shard`
   */
  @SerialVersionUID(1L) final case class EntityStarted(entityId: EntityId) extends StateChange

  /**
   * `State` change for an entity which has terminated.
   */
  @SerialVersionUID(1L) final case class EntityStopped(entityId: EntityId) extends StateChange

  @SerialVersionUID(1L) case object GetCurrentShardState extends ShardQuery

  @SerialVersionUID(1L) final case class CurrentShardState(shardId: ShardRegion.ShardId, entityIds: Set[EntityId])

  @SerialVersionUID(1L) case object GetShardStats extends ShardQuery with ClusterShardingSerializable

  @SerialVersionUID(1L) final case class ShardStats(shardId: ShardRegion.ShardId, entityCount: Int)
      extends ClusterShardingSerializable

  final case class LeaseAcquireResult(acquired: Boolean, reason: Option[Throwable]) extends DeadLetterSuppression
  final case class LeaseLost(reason: Option[Throwable]) extends DeadLetterSuppression

  final case object LeaseRetry extends DeadLetterSuppression
  private val LeaseRetryTimer = "lease-retry"

  object State {
    val Empty = State()
  }

  /**
   * Persistent state of the Shard.
   */
  @SerialVersionUID(1L) final case class State private[akka] (entities: Set[EntityId] = Set.empty)
      extends ClusterShardingSerializable

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
      majorityMinCap: Int): Props = {
    (if (settings.rememberEntities && settings.stateStoreMode == ClusterShardingSettings.StateStoreModeDData) {
       Props(
         new DDataShard(
           typeName,
           shardId,
           entityProps,
           settings,
           extractEntityId,
           extractShardId,
           handOffStopMessage,
           replicator,
           majorityMinCap))
     } else if (settings.rememberEntities && settings.stateStoreMode == ClusterShardingSettings.StateStoreModePersistence)
       Props(
         new PersistentShard(
           typeName,
           shardId,
           entityProps,
           settings,
           extractEntityId,
           extractShardId,
           handOffStopMessage))
     else {
       Props(new Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage))
     }).withDeploy(Deploy.local)
  }

  case object PassivateIdleTick extends NoSerializationVerificationNeeded

}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
@InternalStableApi
private[akka] class Shard(
    typeName: String,
    shardId: ShardRegion.ShardId,
    entityProps: String => Props,
    settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    @unused extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any)
    extends Actor
    with ActorLogging
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

  var state = State.Empty
  var idByRef = Map.empty[ActorRef, EntityId]
  var refById = Map.empty[EntityId, ActorRef]
  var lastMessageTimestamp = Map.empty[EntityId, Long]
  var passivating = Set.empty[ActorRef]
  val messageBuffers = new MessageBufferMap[EntityId]

  private var handOffStopper: Option[ActorRef] = None

  import context.dispatcher
  val passivateIdleTask = if (settings.shouldPassivateIdleEntities) {
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
    log.debug("Shard initialized")
    context.parent ! ShardInitialized(shardId)
    context.become(receiveCommand)
  }

  private def tryGetLease(l: Lease) = {
    log.info("Acquiring lease {}", l.settings)
    pipe(l.acquire(reason => self ! LeaseLost(reason)).map(r => LeaseAcquireResult(r, None)).recover {
      case t => LeaseAcquireResult(acquired = false, Some(t))
    }).to(self)
  }

  def processChange[E <: StateChange](event: E)(handler: E => Unit): Unit =
    handler(event)

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
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender())
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

    if (state.entities(start.entityId)) {
      getOrCreateEntity(start.entityId)
      requester ! ShardRegion.StartEntityAck(start.entityId, shardId)
    } else {
      processChange(EntityStarted(start.entityId)) { evt =>
        getOrCreateEntity(start.entityId)
        sendMsgBuffer(evt)
        requester ! ShardRegion.StartEntityAck(start.entityId, shardId)
      }
    }
  }

  private def receiveStartEntityAck(ack: ShardRegion.StartEntityAck): Unit = {
    if (ack.shardId != shardId && state.entities.contains(ack.entityId)) {
      log.debug("Entity [{}] previously owned by shard [{}] started in shard [{}]", ack.entityId, shardId, ack.shardId)
      processChange(EntityStopped(ack.entityId)) { _ =>
        state = state.copy(state.entities - ack.entityId)
        messageBuffers.remove(ack.entityId)
      }
    }
  }

  private def restartEntities(ids: Set[EntityId]): Unit = {
    context.actorOf(RememberEntityStarter.props(context.parent, ids, settings, sender()))
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
    case GetShardStats        => sender() ! ShardStats(shardId, state.entities.size)
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
    val id = idByRef(ref)
    idByRef -= ref
    refById -= id
    if (passivateIdleTask.isDefined) {
      lastMessageTimestamp -= id
    }
    if (messageBuffers.getOrEmpty(id).nonEmpty) {
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      sendMsgBuffer(EntityStarted(id))
    } else {
      processChange(EntityStopped(id))(passivateCompleted)
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

  // EntityStopped handler
  def passivateCompleted(event: EntityStopped): Unit = {
    val hasBufferedMessages = messageBuffers.getOrEmpty(event.entityId).nonEmpty
    state = state.copy(state.entities - event.entityId)
    if (hasBufferedMessages) {
      log.debug(
        "Entity stopped after passivation [{}], but will be started again due to buffered messages.",
        event.entityId)
      processChange(EntityStarted(event.entityId))(sendMsgBuffer)
    } else {
      log.debug("Entity stopped after passivation [{}]", event.entityId)
      messageBuffers.remove(event.entityId)
    }

  }

  // EntityStarted handler
  def sendMsgBuffer(event: EntityStarted): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrEmpty(event.entityId)
    messageBuffers.remove(event.entityId)

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", event.entityId, messages.size)
      getOrCreateEntity(event.entityId)
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

  def appendToMessageBuffer(id: EntityId, msg: Any, snd: ActorRef): Unit = {
    if (messageBuffers.totalSize >= bufferSize) {
      log.debug("Buffer is full, dropping message for entity [{}]", id)
      context.system.deadLetters ! msg
    } else {
      log.debug("Message for entity [{}] buffered", id)
      messageBuffers.append(id, msg, snd)
    }
  }

  def deliverTo(id: EntityId, @unused msg: Any, payload: Msg, snd: ActorRef): Unit = {
    touchLastMessageTimestamp(id)
    getOrCreateEntity(id).tell(payload, snd)
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
        state = state.copy(state.entities + id)
        touchLastMessageTimestamp(id)
        a
    }
  }

  override def postStop(): Unit = {
    passivateIdleTask.foreach(_.cancel())
  }
}

private[akka] object RememberEntityStarter {
  def props(region: ActorRef, ids: Set[ShardRegion.EntityId], settings: ClusterShardingSettings, requestor: ActorRef) =
    Props(new RememberEntityStarter(region, ids, settings, requestor))

  private case object Tick extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API: Actor responsible for starting entities when rememberEntities is enabled
 */
private[akka] class RememberEntityStarter(
    region: ActorRef,
    ids: Set[ShardRegion.EntityId],
    settings: ClusterShardingSettings,
    requestor: ActorRef)
    extends Actor
    with ActorLogging {

  import RememberEntityStarter.Tick
  import context.dispatcher

  var waitingForAck = ids

  sendStart(ids)

  val tickTask = {
    val resendInterval = settings.tuningParameters.retryInterval
    context.system.scheduler.scheduleWithFixedDelay(resendInterval, resendInterval, self, Tick)
  }

  def sendStart(ids: Set[ShardRegion.EntityId]): Unit = {
    // these go through the region rather the directly to the shard
    // so that shard mapping changes are picked up
    ids.foreach(id => region ! ShardRegion.StartEntity(id))
  }

  override def receive: Receive = {
    case ack: ShardRegion.StartEntityAck =>
      waitingForAck -= ack.entityId
      // inform whoever requested the start that it happened
      requestor ! ack
      if (waitingForAck.isEmpty) context.stop(self)

    case Tick =>
      sendStart(waitingForAck)

  }

  override def postStop(): Unit = {
    tickTask.cancel()
  }
}

/**
 * INTERNAL API: Common things for PersistentShard and DDataShard
 */
private[akka] trait RememberingShard {
  selfType: Shard =>

  import Shard._
  import ShardRegion.EntityId
  import ShardRegion.Msg
  import akka.pattern.pipe

  protected val settings: ClusterShardingSettings

  protected val rememberedEntitiesRecoveryStrategy: EntityRecoveryStrategy = {
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

  protected def restartRememberedEntities(): Unit = {
    rememberedEntitiesRecoveryStrategy.recoverEntities(state.entities).foreach { scheduledRecovery =>
      import context.dispatcher
      scheduledRecovery.filter(_.nonEmpty).map(RestartEntities).pipeTo(self)
    }
  }

  override def entityTerminated(ref: ActorRef): Unit = {
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
      sendMsgBuffer(EntityStarted(id))
    } else {
      if (!passivating.contains(ref)) {
        log.debug("Entity [{}] stopped without passivating, will restart after backoff", id)
        // note that it's not removed from state here, will be started again via RestartEntity
        import context.dispatcher
        context.system.scheduler.scheduleOnce(entityRestartBackoff, self, RestartEntity(id))
      } else processChange(EntityStopped(id))(passivateCompleted)
    }

    passivating = passivating - ref
  }

  override def deliverTo(id: EntityId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(actor) =>
        touchLastMessageTimestamp(id)
        actor.tell(payload, snd)
      case None =>
        if (state.entities.contains(id)) {
          // this may happen when entity is stopped without passivation
          require(!messageBuffers.contains(id), s"Message buffers contains id [$id].")
          getOrCreateEntity(id).tell(payload, snd)
        } else {
          //Note; we only do this if remembering, otherwise the buffer is an overhead
          messageBuffers.append(id, msg, snd)
          processChange(EntityStarted(id))(sendMsgBuffer)
        }
    }
  }
}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for. It is used when `rememberEntities` is enabled and
 * `state-store-mode=persistence`.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class PersistentShard(
    typeName: String,
    shardId: ShardRegion.ShardId,
    entityProps: String => Props,
    override val settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any)
    extends Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
    with RememberingShard
    with PersistentActor
    with ActorLogging {

  import Shard._
  import settings.tuningParameters._

  override def preStart(): Unit = {
    // override to not acquire the lease on start up, acquire after persistent recovery
  }

  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receive = receiveCommand

  override def processChange[E <: StateChange](event: E)(handler: E => Unit): Unit = {
    saveSnapshotWhenNeeded()
    persist(event)(handler)
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }

  override def receiveRecover: Receive = {
    case EntityStarted(id)                 => state = state.copy(state.entities + id)
    case EntityStopped(id)                 => state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted =>
      acquireLeaseIfNeeded() // onLeaseAcquired called when completed
      log.debug("PersistentShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def onLeaseAcquired(): Unit = {
    log.debug("Shard initialized")
    context.parent ! ShardInitialized(shardId)
    context.become(receiveCommand)
    restartRememberedEntities()
    unstashAll()
  }

  override def receiveCommand: Receive =
    ({
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

    }: Receive).orElse(super.receiveCommand)

}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for. It is used when `rememberEntities` is enabled and
 * `state-store-mode=ddata`.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class DDataShard(
    typeName: String,
    shardId: ShardRegion.ShardId,
    entityProps: String => Props,
    override val settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
    with RememberingShard
    with Stash
    with ActorLogging {

  import Shard._
  import ShardRegion.EntityId
  import settings.tuningParameters._

  private val readMajority = ReadMajority(settings.tuningParameters.waitingForStateTimeout, majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  private val maxUpdateAttempts = 3

  implicit private val node = Cluster(context.system)
  implicit private val selfUniqueAddress = SelfUniqueAddress(node.selfUniqueAddress)

  // The default maximum-frame-size is 256 KiB with Artery.
  // When using entity identifiers with 36 character strings (e.g. UUID.randomUUID).
  // By splitting the elements over 5 keys we can support 10000 entities per shard.
  // The Gossip message size of 5 ORSet with 2000 ids is around 200 KiB.
  // This is by intention not configurable because it's important to have the same
  // configuration on each node.
  private val numberOfKeys = 5
  private val stateKeys: Array[ORSetKey[EntityId]] =
    Array.tabulate(numberOfKeys)(i => ORSetKey[EntityId](s"shard-$typeName-$shardId-$i"))

  private var waiting = true

  private def key(entityId: EntityId): ORSetKey[EntityId] = {
    val i = math.abs(entityId.hashCode % numberOfKeys)
    stateKeys(i)
  }

  override def onLeaseAcquired(): Unit = {
    log.info("Lease Acquired. Getting state from DData")
    getState()
    context.become(waitingForState(Set.empty))
  }

  private def getState(): Unit = {
    (0 until numberOfKeys).foreach { i =>
      replicator ! Get(stateKeys(i), readMajority, Some(i))
    }
  }

  override protected[akka] def aroundReceive(rcv: Receive, msg: Any): Unit = {
    super.aroundReceive(rcv, msg)
    if (!waiting)
      unstash() // unstash one message
  }

  override def receive = waitingForState(Set.empty)

  // This state will stash all commands
  private def waitingForState(gotKeys: Set[Int]): Receive = {
    def receiveOne(i: Int): Unit = {
      val newGotKeys = gotKeys + i
      if (newGotKeys.size == numberOfKeys) {
        recoveryCompleted()
      } else
        context.become(waitingForState(newGotKeys))
    }

    {
      case g @ GetSuccess(_, Some(i: Int)) =>
        val key = stateKeys(i)
        state = state.copy(entities = state.entities.union(g.get(key).elements))
        receiveOne(i)

      case GetFailure(_, _) =>
        log.error(
          "The DDataShard was unable to get an initial state within 'waiting-for-state-timeout': {} millis",
          waitingForStateTimeout.toMillis)
        // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
        context.stop(self)

      case NotFound(_, Some(i: Int)) =>
        receiveOne(i)

      case _ =>
        log.debug("Stashing while waiting for DDataShard initial state")
        stash()
    }
  }

  private def recoveryCompleted(): Unit = {
    log.debug("DDataShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
    waiting = false
    context.parent ! ShardInitialized(shardId)
    context.become(receiveCommand)
    restartRememberedEntities()
  }

  override def processChange[E <: StateChange](event: E)(handler: E => Unit): Unit = {
    waiting = true
    context.become(waitingForUpdate(event, handler), discardOld = false)
    sendUpdate(event, retryCount = 1)
  }

  private def sendUpdate(evt: StateChange, retryCount: Int): Unit = {
    replicator ! Update(key(evt.entityId), ORSet.empty[EntityId], writeMajority, Some((evt, retryCount))) { existing =>
      evt match {
        case EntityStarted(id) => existing :+ id
        case EntityStopped(id) => existing.remove(id)
      }
    }
  }

  // this state will stash all messages until it receives UpdateSuccess
  private def waitingForUpdate[E <: StateChange](evt: E, afterUpdateCallback: E => Unit): Receive = {
    case UpdateSuccess(_, Some((`evt`, _))) =>
      log.debug("The DDataShard state was successfully updated with {}", evt)
      waiting = false
      context.unbecome()
      afterUpdateCallback(evt)

    case UpdateTimeout(_, Some((`evt`, retryCount: Int))) =>
      if (retryCount == maxUpdateAttempts) {
        // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
        log.error(
          "The DDataShard was unable to update state after {} attempts, within 'updating-state-timeout'={} millis, event={}. " +
          "Shard will be restarted after backoff.",
          maxUpdateAttempts,
          updatingStateTimeout.toMillis,
          evt)
        context.stop(self)
      } else {
        log.warning(
          "The DDataShard was unable to update state, attempt {} of {}, within 'updating-state-timeout'={} millis, event={}",
          retryCount,
          maxUpdateAttempts,
          updatingStateTimeout.toMillis,
          evt)
        sendUpdate(evt, retryCount + 1)
      }

    case StoreFailure(_, Some((`evt`, _))) =>
      log.error(
        "The DDataShard was unable to update state with event {} due to StoreFailure. " +
        "Shard will be restarted after backoff.",
        evt)
      context.stop(self)

    case ModifyFailure(_, error, cause, Some((`evt`, _))) =>
      log.error(
        cause,
        "The DDataShard was unable to update state with event {} due to ModifyFailure. " +
        "Shard will be restarted. {}",
        evt,
        error)
      throw cause

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
    case msg if extractEntityId.isDefinedAt(msg) => deliverOrBufferMessage(msg, evt)
    case msg                                     =>
      // shouldn't be any other message types, but just in case
      log.debug("Stashing unexpected message [{}] while waiting for DDataShard update of {}", msg.getClass, evt)
      stash()
  }

  /**
   * If the message is for the same entity as we are waiting for the update it will be added to
   * its messageBuffer, which will be sent after the update has completed.
   *
   * If the message is for another entity that is already started (and not in progress of passivating)
   * it will be delivered immediately.
   *
   * Otherwise it will be stashed, and processed after the update has been completed.
   */
  private def deliverOrBufferMessage(msg: Any, waitingForUpdateEvent: StateChange): Unit = {
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
          if (id == waitingForUpdateEvent.entityId) {
            appendToMessageBuffer(id, msg, sender())
          } else {
            val name = URLEncoder.encode(id, "utf-8")
            // messageBuffers.contains(id) when passivation is in progress
            if (!messageBuffers.contains(id) && context.child(name).nonEmpty) {
              deliverTo(id, msg, payload, sender())
            } else {
              log.debug("Stashing to [{}] while waiting for DDataShard update of {}", id, waitingForUpdateEvent)
              stash()
            }
          }
      }
    }
  }

}

object EntityRecoveryStrategy {
  def allStrategy(): EntityRecoveryStrategy = new AllAtOnceEntityRecoveryStrategy()

  def constantStrategy(
      actorSystem: ActorSystem,
      frequency: FiniteDuration,
      numberOfEntities: Int): EntityRecoveryStrategy =
    new ConstantRateEntityRecoveryStrategy(actorSystem, frequency, numberOfEntities)
}

trait EntityRecoveryStrategy {

  import scala.concurrent.Future

  import ShardRegion.EntityId

  def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]]
}

final class AllAtOnceEntityRecoveryStrategy extends EntityRecoveryStrategy {

  import ShardRegion.EntityId

  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    if (entities.isEmpty) Set.empty else Set(Future.successful(entities))
}

final class ConstantRateEntityRecoveryStrategy(
    actorSystem: ActorSystem,
    frequency: FiniteDuration,
    numberOfEntities: Int)
    extends EntityRecoveryStrategy {

  import ShardRegion.EntityId
  import actorSystem.dispatcher
  import akka.pattern.after

  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    entities
      .grouped(numberOfEntities)
      .foldLeft((frequency, Set[Future[Set[EntityId]]]())) {
        case ((interval, scheduledEntityIds), entityIds) =>
          (interval + frequency, scheduledEntityIds + scheduleEntities(interval, entityIds))
      }
      ._2

  private def scheduleEntities(interval: FiniteDuration, entityIds: Set[EntityId]): Future[Set[EntityId]] =
    after(interval, actorSystem.scheduler)(Future.successful[Set[EntityId]](entityIds))
}
