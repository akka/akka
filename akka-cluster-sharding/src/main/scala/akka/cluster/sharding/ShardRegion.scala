/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.net.URLEncoder

import akka.pattern.AskTimeoutException
import akka.util.{ MessageBufferMap, PrettyDuration, Timeout }
import akka.pattern.{ ask, pipe }
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.concurrent.Promise
import akka.Done
import akka.annotation.InternalApi
import akka.cluster.ClusterSettings
import akka.cluster.ClusterSettings.DataCenter

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardRegion {

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor.
   */
  private[akka] def props(
      typeName: String,
      entityProps: String => Props,
      settings: ClusterShardingSettings,
      coordinatorPath: String,
      extractEntityId: ShardRegion.ExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId,
      handOffStopMessage: Any,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(
      new ShardRegion(
        typeName,
        Some(entityProps),
        dataCenter = None,
        settings,
        coordinatorPath,
        extractEntityId,
        extractShardId,
        handOffStopMessage,
        replicator,
        majorityMinCap)).withDeploy(Deploy.local)

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor
   * when using it in proxy only mode.
   */
  private[akka] def proxyProps(
      typeName: String,
      dataCenter: Option[DataCenter],
      settings: ClusterShardingSettings,
      coordinatorPath: String,
      extractEntityId: ShardRegion.ExtractEntityId,
      extractShardId: ShardRegion.ExtractShardId,
      replicator: ActorRef,
      majorityMinCap: Int): Props =
    Props(
      new ShardRegion(
        typeName,
        None,
        dataCenter,
        settings,
        coordinatorPath,
        extractEntityId,
        extractShardId,
        PoisonPill,
        replicator,
        majorityMinCap)).withDeploy(Deploy.local)

  /**
   * Marker type of entity identifier (`String`).
   */
  type EntityId = String

  /**
   * Marker type of shard identifier (`String`).
   */
  type ShardId = String

  /**
   * Marker type of application messages (`Any`).
   */
  type Msg = Any

  /**
   * Interface of the partial function used by the [[ShardRegion]] to
   * extract the entity id and the message to send to the entity from an
   * incoming message. The implementation is application specific.
   * If the partial function does not match the message will be
   * `unhandled`, i.e. posted as `Unhandled` messages on the event stream.
   * Note that the extracted  message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entity actor.
   */
  type ExtractEntityId = PartialFunction[Msg, (EntityId, Msg)]

  /**
   * Interface of the function used by the [[ShardRegion]] to
   * extract the shard id from an incoming message.
   * Only messages that passed the [[ExtractEntityId]] will be used
   * as input to this function.
   */
  type ExtractShardId = Msg => ShardId

  /**
   * Java API: Interface of functions to extract entity id,
   * shard id, and the message to send to the entity from an
   * incoming message.
   */
  trait MessageExtractor {

    /**
     * Extract the entity id from an incoming `message`. If `null` is returned
     * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
     */
    def entityId(message: Any): String

    /**
     * Extract the message to send to the entity from an incoming `message`.
     * Note that the extracted message does not have to be the same as the incoming
     * message to support wrapping in message envelope that is unwrapped before
     * sending to the entity actor.
     */
    def entityMessage(message: Any): Any

    /**
     * Extract the shard id from an incoming `message`. Only messages that passed the [[#entityId]]
     * function will be used as input to this function.
     */
    def shardId(message: Any): String
  }

  object HashCodeMessageExtractor {

    /** INTERNAL API */
    @InternalApi
    private[sharding] def shardId(id: String, maxNumberOfShards: Int): String = {
      // It would be better to have abs(id.hashCode % maxNumberOfShards), see issue #25034
      // but to avoid getting different values when rolling upgrade we keep the old way,
      // and it doesn't have any serious consequences
      (math.abs(id.hashCode) % maxNumberOfShards).toString
    }
  }

  /**
   * Convenience implementation of [[ShardRegion.MessageExtractor]] that
   * construct `shardId` based on the `hashCode` of the `entityId`. The number
   * of unique shards is limited by the given `maxNumberOfShards`.
   */
  abstract class HashCodeMessageExtractor(maxNumberOfShards: Int) extends MessageExtractor {

    /**
     * Default implementation pass on the message as is.
     */
    override def entityMessage(message: Any): Any = message

    override def shardId(message: Any): String = {
      val id = message match {
        case ShardRegion.StartEntity(entityId) => entityId
        case _                                 => entityId(message)
      }
      HashCodeMessageExtractor.shardId(id, maxNumberOfShards)
    }
  }

  sealed trait ShardRegionCommand

  /**
   * If the state of the entities are persistent you may stop entities that are not used to
   * reduce memory consumption. This is done by the application specific implementation of
   * the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
   * If a message is already enqueued to the entity when it stops itself the enqueued message
   * in the mailbox will be dropped. To support graceful passivation without losing such
   * messages the entity actor can send this `Passivate` message to its parent `ShardRegion`.
   * The specified wrapped `stopMessage` will be sent back to the entity, which is
   * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
   * between reception of `Passivate` and termination of the entity. Such buffered messages
   * are thereafter delivered to a new incarnation of the entity.
   *
   * [[akka.actor.PoisonPill]] is a perfectly fine `stopMessage`.
   */
  @SerialVersionUID(1L) final case class Passivate(stopMessage: Any) extends ShardRegionCommand

  /*
   * Send this message to the `ShardRegion` actor to handoff all shards that are hosted by
   * the `ShardRegion` and then the `ShardRegion` actor will be stopped. You can `watch`
   * it to know when it is completed.
   */
  @SerialVersionUID(1L) final case object GracefulShutdown extends ShardRegionCommand

  /**
   * We must be sure that a shard is initialized before to start send messages to it.
   * Shard could be terminated during initialization.
   */
  final case class ShardInitialized(shardId: ShardId)

  /**
   * Java API: Send this message to the `ShardRegion` actor to handoff all shards that are hosted by
   * the `ShardRegion` and then the `ShardRegion` actor will be stopped. You can `watch`
   * it to know when it is completed.
   */
  def gracefulShutdownInstance = GracefulShutdown

  sealed trait ShardRegionQuery

  /**
   * Send this message to the `ShardRegion` actor to request for [[CurrentRegions]],
   * which contains the addresses of all registered regions.
   * Intended for testing purpose to see when cluster sharding is "ready" or to monitor
   * the state of the shard regions.
   */
  @SerialVersionUID(1L) final case object GetCurrentRegions extends ShardRegionQuery

  /**
   * Java API:
   */
  def getCurrentRegionsInstance = GetCurrentRegions

  /**
   * Reply to `GetCurrentRegions`
   */
  @SerialVersionUID(1L) final case class CurrentRegions(regions: Set[Address]) {

    /**
     * Java API
     */
    def getRegions: java.util.Set[Address] = {
      import scala.collection.JavaConverters._
      regions.asJava
    }

  }

  /**
   * Send this message to the `ShardRegion` actor to request for [[ClusterShardingStats]],
   * which contains statistics about the currently running sharded entities in the
   * entire cluster. If the `timeout` is reached without answers from all shard regions
   * the reply will contain an empty map of regions.
   *
   * Intended for testing purpose to see when cluster sharding is "ready" or to monitor
   * the state of the shard regions.
   */
  @SerialVersionUID(1L) case class GetClusterShardingStats(timeout: FiniteDuration) extends ShardRegionQuery

  /**
   * Reply to [[GetClusterShardingStats]], contains statistics about all the sharding regions
   * in the cluster.
   */
  @SerialVersionUID(1L) final case class ClusterShardingStats(regions: Map[Address, ShardRegionStats]) {

    /**
     * Java API
     */
    def getRegions(): java.util.Map[Address, ShardRegionStats] = {
      import scala.collection.JavaConverters._
      regions.asJava
    }
  }

  /**
   * Send this message to the `ShardRegion` actor to request for [[ShardRegionStats]],
   * which contains statistics about the currently running sharded entities in the
   * entire region.
   * Intended for testing purpose to see when cluster sharding is "ready" or to monitor
   * the state of the shard regions.
   *
   * For the statistics for the entire cluster, see [[GetClusterShardingStats$]].
   */
  @SerialVersionUID(1L) case object GetShardRegionStats extends ShardRegionQuery

  /**
   * Java API:
   */
  def getRegionStatsInstance = GetShardRegionStats

  @SerialVersionUID(1L) final case class ShardRegionStats(stats: Map[ShardId, Int]) {

    /**
     * Java API
     */
    def getStats(): java.util.Map[ShardId, Int] = {
      import scala.collection.JavaConverters._
      stats.asJava
    }

  }

  /**
   * Send this message to a `ShardRegion` actor instance to request a
   * [[CurrentShardRegionState]] which describes the current state of the region.
   * The state contains information about what shards are running in this region
   * and what entities are running on each of those shards.
   */
  @SerialVersionUID(1L) case object GetShardRegionState extends ShardRegionQuery

  /**
   * Java API:
   */
  def getShardRegionStateInstance = GetShardRegionState

  /**
   * Reply to [[GetShardRegionState$]]
   *
   * If gathering the shard information times out the set of shards will be empty.
   */
  @SerialVersionUID(1L) final case class CurrentShardRegionState(shards: Set[ShardState]) {

    /**
     * Java API:
     *
     * If gathering the shard information times out the set of shards will be empty.
     */
    def getShards(): java.util.Set[ShardState] = {
      import scala.collection.JavaConverters._
      shards.asJava
    }
  }

  @SerialVersionUID(1L) final case class ShardState(shardId: ShardId, entityIds: Set[EntityId]) {

    /**
     * Java API:
     */
    def getEntityIds(): java.util.Set[EntityId] = {
      import scala.collection.JavaConverters._
      entityIds.asJava
    }
  }

  private case object Retry extends ShardRegionCommand

  /**
   * When an remembering entities and the shard stops unexpected (e.g. persist failure), we
   * restart it after a back off using this message.
   */
  private final case class RestartShard(shardId: ShardId)

  /**
   * When remembering entities and a shard is started, each entity id that needs to
   * be running will trigger this message being sent through sharding. For this to work
   * the message *must* be handled by the shard id extractor.
   */
  final case class StartEntity(entityId: EntityId) extends ClusterShardingSerializable

  /**
   * Sent back when a `ShardRegion.StartEntity` message was received and triggered the entity
   * to start (it does not guarantee the entity successfully started)
   */
  final case class StartEntityAck(entityId: EntityId, shardId: ShardRegion.ShardId) extends ClusterShardingSerializable

  /**
   * INTERNAL API. Sends stopMessage (e.g. `PoisonPill`) to the entities and when all of
   * them have terminated it replies with `ShardStopped`.
   * If the entities don't terminate after `handoffTimeout` it will try stopping them forcefully.
   */
  private[akka] class HandOffStopper(
      shard: String,
      replyTo: ActorRef,
      entities: Set[ActorRef],
      stopMessage: Any,
      handoffTimeout: FiniteDuration)
      extends Actor
      with ActorLogging {
    import ShardCoordinator.Internal.ShardStopped

    context.setReceiveTimeout(handoffTimeout)

    entities.foreach { a =>
      context.watch(a)
      a ! stopMessage
    }

    var remaining = entities

    def receive = {
      case ReceiveTimeout =>
        log.warning(
          "HandOffStopMessage[{}] is not handled by some of the entities of the `{}` shard, " +
          "stopping the remaining entities.",
          stopMessage.getClass.getName,
          shard)

        remaining.foreach { ref =>
          context.stop(ref)
        }

      case Terminated(ref) =>
        remaining -= ref
        if (remaining.isEmpty) {
          replyTo ! ShardStopped(shard)
          context.stop(self)
        }
    }
  }

  private[akka] def handOffStopperProps(
      shard: String,
      replyTo: ActorRef,
      entities: Set[ActorRef],
      stopMessage: Any,
      handoffTimeout: FiniteDuration): Props =
    Props(new HandOffStopper(shard, replyTo, entities, stopMessage, handoffTimeout)).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand for the shards that it is told to be
 * responsible for. It delegates messages targeted to other shards to the responsible
 * `ShardRegion` actor on other nodes.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class ShardRegion(
    typeName: String,
    entityProps: Option[String => Props],
    dataCenter: Option[DataCenter],
    settings: ClusterShardingSettings,
    coordinatorPath: String,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any,
    replicator: ActorRef,
    majorityMinCap: Int)
    extends Actor
    with ActorLogging {

  import ShardCoordinator.Internal._
  import ShardRegion._
  import settings._
  import settings.tuningParameters._

  val cluster = Cluster(context.system)

  // sort by age, oldest first
  val ageOrdering = Member.ageOrdering
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  var regions = Map.empty[ActorRef, Set[ShardId]]
  var regionByShard = Map.empty[ShardId, ActorRef]
  var shardBuffers = new MessageBufferMap[ShardId]
  var loggedFullBufferWarning = false
  var shards = Map.empty[ShardId, ActorRef]
  var shardsByRef = Map.empty[ActorRef, ShardId]
  var startingShards = Set.empty[ShardId]
  var handingOff = Set.empty[ActorRef]
  var gracefulShutdownInProgress = false

  import context.dispatcher
  val retryTask = context.system.scheduler.schedule(retryInterval, retryInterval, self, Retry)
  var retryCount = 0

  // for CoordinatedShutdown
  val gracefulShutdownProgress = Promise[Done]()
  CoordinatedShutdown(context.system)
    .addTask(CoordinatedShutdown.PhaseClusterShardingShutdownRegion, "region-shutdown") { () =>
      if (cluster.isTerminated || cluster.selfMember.status == MemberStatus.Down) {
        Future.successful(Done)
      } else {
        self ! GracefulShutdown
        gracefulShutdownProgress.future
      }
    }

  // subscribe to MemberEvent, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
    if (settings.passivateIdleEntityAfter > Duration.Zero)
      log.info("Idle entities will be passivated after [{}]", PrettyDuration.format(settings.passivateIdleEntityAfter))
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
    gracefulShutdownProgress.trySuccess(Done)
    retryTask.cancel()
  }

  // when using proxy the data center can be different from the own data center
  private val targetDcRole = dataCenter match {
    case Some(t) => ClusterSettings.DcRolePrefix + t
    case None    => ClusterSettings.DcRolePrefix + cluster.settings.SelfDataCenter
  }

  def matchingRole(member: Member): Boolean =
    member.hasRole(targetDcRole) && role.forall(member.hasRole)

  def coordinatorSelection: Option[ActorSelection] =
    membersByAge.headOption.map(m => context.actorSelection(RootActorPath(m.address) + coordinatorPath))

  /**
   * When leaving the coordinator singleton is started rather quickly on next
   * oldest node and therefore it is good to send the GracefulShutdownReq to
   * the likely locations of the coordinator.
   */
  def gracefulShutdownCoordinatorSelections: List[ActorSelection] =
    membersByAge.take(2).toList.map(m => context.actorSelection(RootActorPath(m.address) + coordinatorPath))

  var coordinator: Option[ActorRef] = None

  def changeMembers(newMembers: immutable.SortedSet[Member]): Unit = {
    val before = membersByAge.headOption
    val after = newMembers.headOption
    membersByAge = newMembers
    if (before != after) {
      if (log.isDebugEnabled)
        log.debug(
          "Coordinator moved from [{}] to [{}]",
          before.map(_.address).getOrElse(""),
          after.map(_.address).getOrElse(""))
      coordinator = None
      register()
    }
  }

  def receive: Receive = {
    case Terminated(ref)                         => receiveTerminated(ref)
    case ShardInitialized(shardId)               => initializeShard(shardId, sender())
    case evt: ClusterDomainEvent                 => receiveClusterEvent(evt)
    case state: CurrentClusterState              => receiveClusterState(state)
    case msg: CoordinatorMessage                 => receiveCoordinatorMessage(msg)
    case cmd: ShardRegionCommand                 => receiveCommand(cmd)
    case query: ShardRegionQuery                 => receiveQuery(query)
    case msg: RestartShard                       => deliverMessage(msg, sender())
    case msg: StartEntity                        => deliverStartEntity(msg, sender())
    case msg if extractEntityId.isDefinedAt(msg) => deliverMessage(msg, sender())
    case unknownMsg =>
      log.warning(
        "Message does not have an extractor defined in shard [{}] so it was ignored: {}",
        typeName,
        unknownMsg)
  }

  def receiveClusterState(state: CurrentClusterState): Unit = {
    changeMembers(
      immutable.SortedSet
        .empty(ageOrdering)
        .union(state.members.filter(m => m.status == MemberStatus.Up && matchingRole(m))))
  }

  def receiveClusterEvent(evt: ClusterDomainEvent): Unit = evt match {
    case MemberUp(m) =>
      if (matchingRole(m))
        // replace, it's possible that the upNumber is changed
        changeMembers(membersByAge.filterNot(_.uniqueAddress == m.uniqueAddress) + m)

    case MemberRemoved(m, _) =>
      if (m.uniqueAddress == cluster.selfUniqueAddress)
        context.stop(self)
      else if (matchingRole(m))
        changeMembers(membersByAge.filterNot(_.uniqueAddress == m.uniqueAddress))

    case MemberDowned(m) =>
      if (m.uniqueAddress == cluster.selfUniqueAddress) {
        log.info("Self downed, stopping ShardRegion [{}]", self.path)
        context.stop(self)
      }

    case _: MemberEvent => // these are expected, no need to warn about them

    case _ => unhandled(evt)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HostShard(shard) =>
      log.debug("Host Shard [{}] ", shard)
      regionByShard = regionByShard.updated(shard, self)
      regions = regions.updated(self, regions.getOrElse(self, Set.empty) + shard)

      //Start the shard, if already started this does nothing
      getShard(shard)

      sender() ! ShardStarted(shard)

    case ShardHome(shard, ref) =>
      log.debug("Shard [{}] located at [{}]", shard, ref)
      regionByShard.get(shard) match {
        case Some(r) if r == self && ref != self =>
          // should not happen, inconsistency between ShardRegion and ShardCoordinator
          throw new IllegalStateException(s"Unexpected change of shard [$shard] from self to [$ref]")
        case _ =>
      }
      regionByShard = regionByShard.updated(shard, ref)
      regions = regions.updated(ref, regions.getOrElse(ref, Set.empty) + shard)

      if (ref != self)
        context.watch(ref)

      if (ref == self)
        getShard(shard).foreach(deliverBufferedMessages(shard, _))
      else
        deliverBufferedMessages(shard, ref)

    case RegisterAck(coord) =>
      context.watch(coord)
      coordinator = Some(coord)
      requestShardBufferHomes()

    case BeginHandOff(shard) =>
      log.debug("BeginHandOff shard [{}]", shard)
      if (regionByShard.contains(shard)) {
        val regionRef = regionByShard(shard)
        val updatedShards = regions(regionRef) - shard
        if (updatedShards.isEmpty) regions -= regionRef
        else regions = regions.updated(regionRef, updatedShards)
        regionByShard -= shard
      }
      sender() ! BeginHandOffAck(shard)

    case msg @ HandOff(shard) =>
      log.debug("HandOff shard [{}]", shard)

      // must drop requests that came in between the BeginHandOff and now,
      // because they might be forwarded from other regions and there
      // is a risk or message re-ordering otherwise
      if (shardBuffers.contains(shard)) {
        shardBuffers.remove(shard)
        loggedFullBufferWarning = false
      }

      if (shards.contains(shard)) {
        handingOff += shards(shard)
        shards(shard).forward(msg)
      } else
        sender() ! ShardStopped(shard)

    case _ => unhandled(msg)

  }

  def receiveCommand(cmd: ShardRegionCommand): Unit = cmd match {
    case Retry =>
      sendGracefulShutdownToCoordinator()

      if (shardBuffers.nonEmpty)
        retryCount += 1
      if (coordinator.isEmpty)
        register()
      else {
        requestShardBufferHomes()
      }

      tryCompleteGracefulShutdown()

    case GracefulShutdown =>
      log.debug("Starting graceful shutdown of region and all its shards")
      gracefulShutdownInProgress = true
      sendGracefulShutdownToCoordinator()
      tryCompleteGracefulShutdown()

    case _ => unhandled(cmd)
  }

  def receiveQuery(query: ShardRegionQuery): Unit = query match {
    case GetCurrentRegions =>
      coordinator match {
        case Some(c) => c.forward(GetCurrentRegions)
        case None    => sender() ! CurrentRegions(Set.empty)
      }

    case GetShardRegionState =>
      replyToRegionStateQuery(sender())

    case GetShardRegionStats =>
      replyToRegionStatsQuery(sender())

    case msg: GetClusterShardingStats =>
      coordinator.fold(sender ! ClusterShardingStats(Map.empty))(_.forward(msg))

    case _ => unhandled(query)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (coordinator.contains(ref))
      coordinator = None
    else if (regions.contains(ref)) {
      val shards = regions(ref)
      regionByShard --= shards
      regions -= ref
      if (log.isDebugEnabled)
        log.debug("Region [{}] with shards [{}] terminated", ref, shards.mkString(", "))
    } else if (shardsByRef.contains(ref)) {
      val shardId: ShardId = shardsByRef(ref)

      shardsByRef = shardsByRef - ref
      shards = shards - shardId
      startingShards -= shardId
      if (handingOff.contains(ref)) {
        handingOff = handingOff - ref
        log.debug("Shard [{}] handoff complete", shardId)
      } else {
        // if persist fails it will stop
        log.debug("Shard [{}]  terminated while not being handed off", shardId)
        if (rememberEntities) {
          context.system.scheduler.scheduleOnce(shardFailureBackoff, self, RestartShard(shardId))
        }
      }

      tryCompleteGracefulShutdown()
    }
  }

  def replyToRegionStateQuery(ref: ActorRef): Unit = {
    askAllShards[Shard.CurrentShardState](Shard.GetCurrentShardState)
      .map { shardStates =>
        CurrentShardRegionState(shardStates.map {
          case (shardId, state) => ShardRegion.ShardState(shardId, state.entityIds)
        }.toSet)
      }
      .recover {
        case _: AskTimeoutException => CurrentShardRegionState(Set.empty)
      }
      .pipeTo(ref)
  }

  def replyToRegionStatsQuery(ref: ActorRef): Unit = {
    askAllShards[Shard.ShardStats](Shard.GetShardStats)
      .map { shardStats =>
        ShardRegionStats(shardStats.map {
          case (shardId, stats) => (shardId, stats.entityCount)
        }.toMap)
      }
      .recover {
        case x: AskTimeoutException => ShardRegionStats(Map.empty)
      }
      .pipeTo(ref)
  }

  def askAllShards[T: ClassTag](msg: Any): Future[Seq[(ShardId, T)]] = {
    implicit val timeout: Timeout = 3.seconds
    Future.sequence(shards.toSeq.map {
      case (shardId, ref) => (ref ? msg).mapTo[T].map(t => (shardId, t))
    })
  }

  private def tryCompleteGracefulShutdown() =
    if (gracefulShutdownInProgress && shards.isEmpty && shardBuffers.isEmpty) {
      context.stop(self) // all shards have been rebalanced, complete graceful shutdown
    }

  def register(): Unit = {
    coordinatorSelection.foreach(_ ! registrationMessage)
    if (shardBuffers.nonEmpty && retryCount >= 5) coordinatorSelection match {
      case Some(actorSelection) =>
        val coordinatorMessage =
          if (cluster.state.unreachable(membersByAge.head)) s"Coordinator [${membersByAge.head}] is unreachable."
          else s"Coordinator [${membersByAge.head}] is reachable."
        log.warning(
          "Trying to register to coordinator at [{}], but no acknowledgement. Total [{}] buffered messages. [{}]",
          actorSelection,
          shardBuffers.totalSize,
          coordinatorMessage)
      case None =>
        log.warning(
          "No coordinator found to register. Probably, no seed-nodes configured and manual cluster join not performed? Total [{}] buffered messages.",
          shardBuffers.totalSize)
    }
  }

  def registrationMessage: Any =
    if (entityProps.isDefined) Register(self) else RegisterProxy(self)

  def requestShardBufferHomes(): Unit = {
    shardBuffers.foreach {
      case (shard, buf) =>
        coordinator.foreach { c =>
          val logMsg = "Retry request for shard [{}] homes from coordinator at [{}]. [{}] buffered messages."
          if (retryCount >= 5)
            log.warning(logMsg, shard, c, buf.size)
          else
            log.debug(logMsg, shard, c, buf.size)

          c ! GetShardHome(shard)
        }
    }
  }

  def initializeShard(id: ShardId, shard: ActorRef): Unit = {
    log.debug("Shard was initialized {}", id)
    startingShards -= id
    deliverBufferedMessages(id, shard)
  }

  def bufferMessage(shardId: ShardId, msg: Any, snd: ActorRef) = {
    val totBufSize = shardBuffers.totalSize
    if (totBufSize >= bufferSize) {
      if (loggedFullBufferWarning)
        log.debug("Buffer is full, dropping message for shard [{}]", shardId)
      else {
        log.warning("Buffer is full, dropping message for shard [{}]", shardId)
        loggedFullBufferWarning = true
      }
      context.system.deadLetters ! msg
    } else {
      shardBuffers.append(shardId, msg, snd)

      // log some insight to how buffers are filled up every 10% of the buffer capacity
      val tot = totBufSize + 1
      if (tot % (bufferSize / 10) == 0) {
        val logMsg = s"ShardRegion for [$typeName] is using [${100.0 * tot / bufferSize} %] of its buffer capacity."
        if (tot <= bufferSize / 2)
          log.info(logMsg)
        else
          log.warning(
            logMsg + " The coordinator might not be available. You might want to check cluster membership status.")
      }
    }
  }

  def deliverBufferedMessages(shardId: ShardId, receiver: ActorRef): Unit = {
    if (shardBuffers.contains(shardId)) {
      val buf = shardBuffers.getOrEmpty(shardId)
      log.debug("Deliver [{}] buffered messages for shard [{}]", buf.size, shardId)
      buf.foreach { case (msg, snd) => receiver.tell(msg, snd) }
      shardBuffers.remove(shardId)
    }
    loggedFullBufferWarning = false
    retryCount = 0
  }

  def deliverStartEntity(msg: StartEntity, snd: ActorRef): Unit = {
    try {
      deliverMessage(msg, snd)
    } catch {
      case ex: MatchError =>
        log.error(ex, "When using remember-entities the shard id extractor must handle ShardRegion.StartEntity(id).")
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit =
    msg match {
      case RestartShard(shardId) =>
        regionByShard.get(shardId) match {
          case Some(ref) =>
            if (ref == self)
              getShard(shardId)
          case None =>
            if (!shardBuffers.contains(shardId)) {
              log.debug("Request shard [{}] home. Coordinator [{}]", shardId, coordinator)
              coordinator.foreach(_ ! GetShardHome(shardId))
            }
            val buf = shardBuffers.getOrEmpty(shardId)
            log.debug("Buffer message for shard [{}]. Total [{}] buffered messages.", shardId, buf.size + 1)
            shardBuffers.append(shardId, msg, snd)
        }

      case _ =>
        val shardId = extractShardId(msg)
        regionByShard.get(shardId) match {
          case Some(ref) if ref == self =>
            getShard(shardId) match {
              case Some(shard) =>
                if (shardBuffers.contains(shardId)) {
                  // Since now messages to a shard is buffered then those messages must be in right order
                  bufferMessage(shardId, msg, snd)
                  deliverBufferedMessages(shardId, shard)
                } else shard.tell(msg, snd)
              case None => bufferMessage(shardId, msg, snd)
            }
          case Some(ref) =>
            log.debug("Forwarding request for shard [{}] to [{}]", shardId, ref)
            ref.tell(msg, snd)
          case None if shardId == null || shardId == "" =>
            log.warning("Shard must not be empty, dropping message [{}]", msg.getClass.getName)
            context.system.deadLetters ! msg
          case None =>
            if (!shardBuffers.contains(shardId)) {
              log.debug("Request shard [{}] home. Coordinator [{}]", shardId, coordinator)
              coordinator.foreach(_ ! GetShardHome(shardId))
            }
            bufferMessage(shardId, msg, snd)
        }
    }

  def getShard(id: ShardId): Option[ActorRef] = {
    if (startingShards.contains(id))
      None
    else {
      shards
        .get(id)
        .orElse(entityProps match {
          case Some(props) if !shardsByRef.values.exists(_ == id) =>
            log.debug("Starting shard [{}] in region", id)

            val name = URLEncoder.encode(id, "utf-8")
            val shard = context.watch(
              context.actorOf(
                Shard
                  .props(
                    typeName,
                    id,
                    props,
                    settings,
                    extractEntityId,
                    extractShardId,
                    handOffStopMessage,
                    replicator,
                    majorityMinCap)
                  .withDispatcher(context.props.dispatcher),
                name))
            shardsByRef = shardsByRef.updated(shard, id)
            shards = shards.updated(id, shard)
            startingShards += id
            None
          case Some(props) =>
            None
          case None =>
            throw new IllegalStateException("Shard must not be allocated to a proxy only ShardRegion")
        })
    }
  }

  def sendGracefulShutdownToCoordinator(): Unit = {
    if (gracefulShutdownInProgress)
      gracefulShutdownCoordinatorSelections.foreach(_ ! GracefulShutdownReq(self))
  }
}
