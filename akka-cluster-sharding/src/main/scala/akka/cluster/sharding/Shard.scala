/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import java.net.URLEncoder

import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.sharding.Shard.ShardCommand
import akka.persistence._
import akka.actor.Actor

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.cluster.Cluster
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator._
import akka.actor.Stash
import akka.cluster.ddata.DistributedData

/**
 * INTERNAL API
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] object Shard {
  import ShardRegion.EntityId

  /**
   * A Shard command
   */
  sealed trait ShardCommand

  /**
   * When an remembering entities and the entity stops without issuing a `Passivate`, we
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

  @SerialVersionUID(1L) case object GetShardStats extends ShardQuery

  @SerialVersionUID(1L) final case class ShardStats(shardId: ShardRegion.ShardId, entityCount: Int)

  object State {
    val Empty = State()
  }

  /**
   * Persistent state of the Shard.
   */
  @SerialVersionUID(1L) final case class State private[akka] (
    entities: Set[EntityId] = Set.empty) extends ClusterShardingSerializable

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Shard]] actor.
   * If `settings.rememberEntities` is enabled the `PersistentShard`
   * subclass is used, otherwise `Shard`.
   */
  def props(
    typeName:           String,
    shardId:            ShardRegion.ShardId,
    entityProps:        Props,
    settings:           ClusterShardingSettings,
    extractEntityId:    ShardRegion.ExtractEntityId,
    extractShardId:     ShardRegion.ExtractShardId,
    handOffStopMessage: Any,
    replicator:         ActorRef,
    majorityMinCap:     Int): Props = {
    if (settings.rememberEntities && settings.stateStoreMode == ClusterShardingSettings.StateStoreModeDData) {
      Props(new DDataShard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId,
        handOffStopMessage, replicator, majorityMinCap)).withDeploy(Deploy.local)
    } else if (settings.rememberEntities && settings.stateStoreMode == ClusterShardingSettings.StateStoreModePersistence)
      Props(new PersistentShard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage))
        .withDeploy(Deploy.local)
    else
      Props(new Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage))
        .withDeploy(Deploy.local)
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
private[akka] class Shard(
  typeName:           String,
  shardId:            ShardRegion.ShardId,
  entityProps:        Props,
  settings:           ClusterShardingSettings,
  extractEntityId:    ShardRegion.ExtractEntityId,
  extractShardId:     ShardRegion.ExtractShardId,
  handOffStopMessage: Any) extends Actor with ActorLogging {

  import ShardRegion.{ handOffStopperProps, EntityId, Msg, Passivate, ShardInitialized }
  import ShardCoordinator.Internal.{ HandOff, ShardStopped }
  import Shard._
  import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
  import akka.cluster.sharding.ShardRegion.ShardRegionCommand
  import settings.tuningParameters._

  var state = State.Empty
  var idByRef = Map.empty[ActorRef, EntityId]
  var refById = Map.empty[EntityId, ActorRef]
  var passivating = Set.empty[ActorRef]
  var messageBuffers = Map.empty[EntityId, Vector[(Msg, ActorRef)]]

  var handOffStopper: Option[ActorRef] = None

  initialized()

  def initialized(): Unit = context.parent ! ShardInitialized(shardId)

  def totalBufferSize = messageBuffers.foldLeft(0) { (sum, entity) ⇒ sum + entity._2.size }

  def processChange[E <: StateChange](event: E)(handler: E ⇒ Unit): Unit =
    handler(event)

  def receive = receiveCommand

  def receiveCommand: Receive = {
    case Terminated(ref)                         ⇒ receiveTerminated(ref)
    case msg: CoordinatorMessage                 ⇒ receiveCoordinatorMessage(msg)
    case msg: ShardCommand                       ⇒ receiveShardCommand(msg)
    case msg: ShardRegionCommand                 ⇒ receiveShardRegionCommand(msg)
    case msg: ShardQuery                         ⇒ receiveShardQuery(msg)
    case msg if extractEntityId.isDefinedAt(msg) ⇒ deliverMessage(msg, sender())
  }

  def receiveShardCommand(msg: ShardCommand): Unit = msg match {
    case RestartEntity(id)    ⇒ getEntity(id)
    case RestartEntities(ids) ⇒ ids foreach getEntity
  }

  def receiveShardRegionCommand(msg: ShardRegionCommand): Unit = msg match {
    case Passivate(stopMessage) ⇒ passivate(sender(), stopMessage)
    case _                      ⇒ unhandled(msg)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HandOff(`shardId`) ⇒ handOff(sender())
    case HandOff(shard)     ⇒ log.warning("Shard [{}] can not hand off for another Shard [{}]", shardId, shard)
    case _                  ⇒ unhandled(msg)
  }

  def receiveShardQuery(msg: ShardQuery): Unit = msg match {
    case GetCurrentShardState ⇒ sender() ! CurrentShardState(shardId, refById.keySet)
    case GetShardStats        ⇒ sender() ! ShardStats(shardId, state.entities.size)
  }

  def handOff(replyTo: ActorRef): Unit = handOffStopper match {
    case Some(_) ⇒ log.warning("HandOff shard [{}] received during existing handOff", shardId)
    case None ⇒
      log.debug("HandOff shard [{}]", shardId)

      if (state.entities.nonEmpty) {
        handOffStopper = Some(context.watch(context.actorOf(
          handOffStopperProps(shardId, replyTo, idByRef.keySet, handOffStopMessage))))

        //During hand off we only care about watching for termination of the hand off stopper
        context become {
          case Terminated(ref) ⇒ receiveTerminated(ref)
        }
      } else {
        replyTo ! ShardStopped(shardId)
        context stop self
      }
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (handOffStopper.contains(ref))
      context stop self
    else if (idByRef.contains(ref) && handOffStopper.isEmpty)
      entityTerminated(ref)
  }

  def entityTerminated(ref: ActorRef): Unit = {
    val id = idByRef(ref)
    if (messageBuffers.getOrElse(id, Vector.empty).nonEmpty) {
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      sendMsgBuffer(EntityStarted(id))
    } else {
      processChange(EntityStopped(id))(passivateCompleted)
    }

    passivating = passivating - ref
  }

  def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    idByRef.get(entity) match {
      case Some(id) => if (!messageBuffers.contains(id)) {
        log.debug("Passivating started on entity {}", id)

        passivating = passivating + entity
        messageBuffers = messageBuffers.updated(id, Vector.empty)
        entity ! stopMessage
      } else {
        log.debug("Passivation already in progress for {}. Not sending stopMessage back to entity.", entity)
      }
      case None    => log.debug("Unknown entity {}. Not sending stopMessage back to entity.", entity)
    }
  }

  // EntityStopped handler
  def passivateCompleted(event: EntityStopped): Unit = {
    log.debug("Entity stopped [{}]", event.entityId)

    val ref = refById(event.entityId)
    idByRef -= ref
    refById -= event.entityId

    state = state.copy(state.entities - event.entityId)
    messageBuffers = messageBuffers - event.entityId
  }

  // EntityStarted handler
  def sendMsgBuffer(event: EntityStarted): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrElse(event.entityId, Vector.empty)
    messageBuffers = messageBuffers - event.entityId

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", event.entityId, messages.size)
      getEntity(event.entityId)

      //Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages foreach {
        case (msg, snd) ⇒ deliverMessage(msg, snd)
      }
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      messageBuffers.get(id) match {
        case None ⇒ deliverTo(id, msg, payload, snd)

        case Some(buf) if totalBufferSize >= bufferSize ⇒
          log.debug("Buffer is full, dropping message for entity [{}]", id)
          context.system.deadLetters ! msg

        case Some(buf) ⇒
          log.debug("Message for entity [{}] buffered", id)
          messageBuffers = messageBuffers.updated(id, buf :+ ((msg, snd)))
      }
    }
  }

  def deliverTo(id: EntityId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(actor) ⇒ actor.tell(payload, snd)
      case None        ⇒ getEntity(id).tell(payload, snd)
    }
  }

  def getEntity(id: EntityId): ActorRef = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name).getOrElse {
      log.debug("Starting entity [{}] in shard [{}]", id, shardId)

      val a = context.watch(context.actorOf(entityProps, name))
      idByRef = idByRef.updated(a, id)
      refById = refById.updated(id, a)
      state = state.copy(state.entities + id)
      a
    }
  }
}

/**
 * INTERNAL API: Common things for PersistentShard and DDataShard
 */
private[akka] trait RememberingShard { selfType: Shard ⇒
  import ShardRegion.{ EntityId, Msg }
  import Shard._
  import akka.pattern.pipe

  protected val settings: ClusterShardingSettings

  protected val rememberedEntitiesRecoveryStrategy: EntityRecoveryStrategy = {
    import settings.tuningParameters._
    entityRecoveryStrategy match {
      case "all" ⇒ EntityRecoveryStrategy.allStrategy()
      case "constant" ⇒ EntityRecoveryStrategy.constantStrategy(
        context.system,
        entityRecoveryConstantRateStrategyFrequency,
        entityRecoveryConstantRateStrategyNumberOfEntities)
    }
  }

  protected def restartRememberedEntities(): Unit = {
    rememberedEntitiesRecoveryStrategy.recoverEntities(state.entities).foreach { scheduledRecovery ⇒
      import context.dispatcher
      scheduledRecovery.filter(_.nonEmpty).map(RestartEntities).pipeTo(self)
    }
  }

  override def entityTerminated(ref: ActorRef): Unit = {
    import settings.tuningParameters._
    val id = idByRef(ref)
    if (messageBuffers.getOrElse(id, Vector.empty).nonEmpty) {
      //Note; because we're not persisting the EntityStopped, we don't need
      // to persist the EntityStarted either.
      log.debug("Starting entity [{}] again, there are buffered messages for it", id)
      sendMsgBuffer(EntityStarted(id))
    } else {
      if (!passivating.contains(ref)) {
        log.debug("Entity [{}] stopped without passivating, will restart after backoff", id)
        import context.dispatcher
        context.system.scheduler.scheduleOnce(entityRestartBackoff, self, RestartEntity(id))
      } else processChange(EntityStopped(id))(passivateCompleted)
    }

    passivating = passivating - ref
  }

  override def deliverTo(id: EntityId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(actor) ⇒
        actor.tell(payload, snd)

      case None ⇒
        //Note; we only do this if remembering, otherwise the buffer is an overhead
        messageBuffers = messageBuffers.updated(id, Vector((msg, snd)))
        processChange(EntityStarted(id))(sendMsgBuffer)
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
  typeName:              String,
  shardId:               ShardRegion.ShardId,
  entityProps:           Props,
  override val settings: ClusterShardingSettings,
  extractEntityId:       ShardRegion.ExtractEntityId,
  extractShardId:        ShardRegion.ExtractShardId,
  handOffStopMessage:    Any) extends Shard(
  typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
  with RememberingShard with PersistentActor with ActorLogging {

  import ShardRegion.{ EntityId, Msg }
  import Shard._
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  // would be initialized after recovery completed
  override def initialized(): Unit = {}

  override def receive = receiveCommand

  override def processChange[E <: StateChange](event: E)(handler: E ⇒ Unit): Unit = {
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
    case EntityStarted(id)                 ⇒ state = state.copy(state.entities + id)
    case EntityStopped(id)                 ⇒ state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State) ⇒ state = snapshot
    case RecoveryCompleted ⇒
      restartRememberedEntities()
      super.initialized()
      log.debug("PersistentShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def receiveCommand: Receive = ({
    case SaveSnapshotSuccess(m) ⇒
      log.debug("PersistentShard snapshot saved successfully")
      /*
       * delete old events but keep the latest around because
       *
       * it's not safe to delete all events immediate because snapshots are typically stored with a weaker consistency
       * level which means that a replay might "see" the deleted events before it sees the stored snapshot,
       * i.e. it will use an older snapshot and then not replay the full sequence of events
       *
       * for debugging if something goes wrong in production it's very useful to be able to inspect the events
       */
      val deleteToSequenceNr = m.sequenceNr - keepNrOfBatches * snapshotAfter
      if (deleteToSequenceNr > 0) {
        deleteMessages(deleteToSequenceNr)
      }

    case SaveSnapshotFailure(_, reason) ⇒
      log.warning("PersistentShard snapshot failure: {}", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) ⇒
      log.debug("PersistentShard messages to {} deleted successfully", toSequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = toSequenceNr - 1))

    case DeleteMessagesFailure(reason, toSequenceNr) ⇒
      log.warning("PersistentShard messages to {} deletion failure: {}", toSequenceNr, reason.getMessage)

    case DeleteSnapshotSuccess(m) ⇒
      log.debug("PersistentShard snapshots matching {} deleted successfully", m)

    case DeleteSnapshotFailure(m, reason) ⇒
      log.warning("PersistentShard snapshots matching {} deletion falure: {}", m, reason.getMessage)

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
  typeName:              String,
  shardId:               ShardRegion.ShardId,
  entityProps:           Props,
  override val settings: ClusterShardingSettings,
  extractEntityId:       ShardRegion.ExtractEntityId,
  extractShardId:        ShardRegion.ExtractShardId,
  handOffStopMessage:    Any,
  replicator:            ActorRef,
  majorityMinCap:        Int) extends Shard(
  typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
  with RememberingShard with Stash with ActorLogging {

  import ShardRegion.{ EntityId, Msg }
  import Shard._
  import settings.tuningParameters._

  private val readMajority = ReadMajority(
    settings.tuningParameters.waitingForStateTimeout,
    majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)
  private val maxUpdateAttempts = 3

  implicit private val node = Cluster(context.system)

  // The default maximum-frame-size is 256 KiB with Artery.
  // ORSet with 40000 elements has a size of ~ 200000 bytes.
  // By splitting the elements over 5 keys we can safely support 200000 entities per shard.
  // This is by intention not configurable because it's important to have the same
  // configuration on each node.
  private val numberOfKeys = 5
  private val stateKeys: Array[ORSetKey[EntityId]] =
    Array.tabulate(numberOfKeys)(i ⇒ ORSetKey[EntityId](s"shard-${typeName}-${shardId}-$i"))

  private def key(entityId: EntityId): ORSetKey[EntityId] = {
    val i = (math.abs(entityId.hashCode) % numberOfKeys)
    stateKeys(i)
  }

  // get initial state from ddata replicator
  getState()

  private def getState(): Unit = {
    (0 until numberOfKeys).map { i ⇒
      replicator ! Get(stateKeys(i), readMajority, Some(i))
    }
  }

  // would be initialized after recovery completed
  override def initialized(): Unit = {}

  override def receive = waitingForState(Set.empty)

  // This state will stash all commands
  private def waitingForState(gotKeys: Set[Int]): Receive = {
    def receiveOne(i: Int): Unit = {
      val newGotKeys = gotKeys + i
      if (newGotKeys.size == numberOfKeys)
        recoveryCompleted()
      else
        context.become(waitingForState(newGotKeys))
    }

    {
      case g @ GetSuccess(_, Some(i: Int)) ⇒
        val key = stateKeys(i)
        state = state.copy(entities = state.entities union (g.get(key).elements))
        receiveOne(i)

      case GetFailure(_, _) ⇒
        log.error(
          "The DDataShard was unable to get an initial state within 'waiting-for-state-timeout': {} millis",
          waitingForStateTimeout.toMillis)
        // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
        context.stop(self)

      case NotFound(_, Some(i: Int)) ⇒
        receiveOne(i)

      case _ ⇒
        stash()
    }
  }

  private def recoveryCompleted(): Unit = {
    restartRememberedEntities()
    super.initialized()
    log.debug("DDataShard recovery completed shard [{}] with [{}] entities", shardId, state.entities.size)
    unstashAll()
    context.become(receiveCommand)
  }

  override def processChange[E <: StateChange](event: E)(handler: E ⇒ Unit): Unit = {
    context.become(waitingForUpdate(event, handler), discardOld = false)
    sendUpdate(event, retryCount = 1)
  }

  private def sendUpdate(evt: StateChange, retryCount: Int) = {
    replicator ! Update(key(evt.entityId), ORSet.empty[EntityId], writeMajority,
      Some((evt, retryCount))) { existing ⇒
        evt match {
          case EntityStarted(id) ⇒ existing + id
          case EntityStopped(id) ⇒ existing - id
        }
      }
  }

  // this state will stash all messages until it receives UpdateSuccess
  private def waitingForUpdate[E <: StateChange](evt: E, afterUpdateCallback: E ⇒ Unit): Receive = {
    case UpdateSuccess(_, Some((`evt`, _))) ⇒
      log.debug("The DDataShard state was successfully updated with {}", evt)
      context.unbecome()
      afterUpdateCallback(evt)
      unstashAll()

    case UpdateTimeout(_, Some((`evt`, retryCount: Int))) ⇒
      if (retryCount == maxUpdateAttempts) {
        // parent ShardRegion supervisor will notice that it terminated and will start it again, after backoff
        log.error(
          "The DDataShard was unable to update state after {} attempts, within 'updating-state-timeout'={} millis, event={}. " +
            "Shard will be restarted after backoff.",
          maxUpdateAttempts, updatingStateTimeout.toMillis, evt)
        context.stop(self)
      } else {
        log.warning(
          "The DDataShard was unable to update state, attempt {} of {}, within 'updating-state-timeout'={} millis, event={}",
          retryCount, maxUpdateAttempts, updatingStateTimeout.toMillis, evt)
        sendUpdate(evt, retryCount + 1)
      }

    case ModifyFailure(_, error, cause, Some((`evt`, _))) ⇒
      log.error(
        cause,
        "The DDataShard was unable to update state with error {} and event {}. Shard will be restarted",
        error,
        evt)
      throw cause

    case _ ⇒ stash()
  }

}

object EntityRecoveryStrategy {
  def allStrategy(): EntityRecoveryStrategy = new AllAtOnceEntityRecoveryStrategy()

  def constantStrategy(actorSystem: ActorSystem, frequency: FiniteDuration, numberOfEntities: Int): EntityRecoveryStrategy =
    new ConstantRateEntityRecoveryStrategy(actorSystem, frequency, numberOfEntities)
}

trait EntityRecoveryStrategy {
  import ShardRegion.EntityId
  import scala.concurrent.Future

  def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]]
}

final class AllAtOnceEntityRecoveryStrategy extends EntityRecoveryStrategy {
  import ShardRegion.EntityId
  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    if (entities.isEmpty) Set.empty else Set(Future.successful(entities))
}

final class ConstantRateEntityRecoveryStrategy(actorSystem: ActorSystem, frequency: FiniteDuration, numberOfEntities: Int) extends EntityRecoveryStrategy {
  import ShardRegion.EntityId
  import actorSystem.dispatcher
  import akka.pattern.after

  override def recoverEntities(entities: Set[EntityId]): Set[Future[Set[EntityId]]] =
    entities.grouped(numberOfEntities).foldLeft((frequency, Set[Future[Set[EntityId]]]())) {
      case ((interval, scheduledEntityIds), entityIds) ⇒
        (interval + frequency, scheduledEntityIds + scheduleEntities(interval, entityIds))
    }._2

  private def scheduleEntities(interval: FiniteDuration, entityIds: Set[EntityId]) =
    after(interval, actorSystem.scheduler)(Future.successful[Set[EntityId]](entityIds))
}
