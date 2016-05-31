/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import java.net.URLEncoder
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.sharding.Shard.{ ShardCommand }
import akka.persistence.PersistentActor
import akka.persistence.SnapshotOffer
import akka.actor.Actor
import akka.persistence.RecoveryCompleted
import akka.persistence.SaveSnapshotFailure
import akka.persistence.SaveSnapshotSuccess

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
    handOffStopMessage: Any): Props = {
    if (settings.rememberEntities)
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
  import Shard.{ State, RestartEntity, EntityStopped, EntityStarted }
  import Shard.{ ShardQuery, GetCurrentShardState, CurrentShardState, GetShardStats, ShardStats }
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

  def processChange[A](event: A)(handler: A ⇒ Unit): Unit =
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
    case RestartEntity(id) ⇒ getEntity(id)
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
    if (handOffStopper.exists(_ == ref))
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
      case Some(id) if !messageBuffers.contains(id) ⇒
        log.debug("Passivating started on entity {}", id)

        passivating = passivating + entity
        messageBuffers = messageBuffers.updated(id, Vector.empty)
        entity ! stopMessage

      case _ ⇒ //ignored
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
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for. It is used when `rememberEntities` is enabled.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class PersistentShard(
  typeName:           String,
  shardId:            ShardRegion.ShardId,
  entityProps:        Props,
  settings:           ClusterShardingSettings,
  extractEntityId:    ShardRegion.ExtractEntityId,
  extractShardId:     ShardRegion.ExtractShardId,
  handOffStopMessage: Any) extends Shard(
  typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage)
  with PersistentActor with ActorLogging {

  import ShardRegion.{ EntityId, Msg }
  import Shard.{ State, RestartEntity, EntityStopped, EntityStarted }
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Shard/${shardId}"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  // would be initialized after recovery completed
  override def initialized(): Unit = {}

  override def receive = receiveCommand

  override def processChange[A](event: A)(handler: A ⇒ Unit): Unit = {
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
      state.entities foreach getEntity
      super.initialized()
      log.debug("Shard recovery completed {}", shardId)
  }

  override def receiveCommand: Receive = ({
    case _: SaveSnapshotSuccess ⇒
      log.debug("PersistentShard snapshot saved successfully")
    case SaveSnapshotFailure(_, reason) ⇒
      log.warning("PersistentShard snapshot failure: {}", reason.getMessage)
  }: Receive).orElse(super.receiveCommand)

  override def entityTerminated(ref: ActorRef): Unit = {
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
        saveSnapshotWhenNeeded()
        persist(EntityStarted(id))(sendMsgBuffer)
    }
  }

}
