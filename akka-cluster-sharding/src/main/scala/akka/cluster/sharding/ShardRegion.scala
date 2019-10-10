/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.net.URLEncoder

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.concurrent.Promise
import scala.runtime.AbstractFunction1
import scala.util.Success
import scala.util.Failure

import akka.Done
import akka.annotation.InternalApi
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.ClusterSettings
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.sharding.Shard.ShardStats
import akka.pattern.{ ask, pipe }
import akka.util.{ MessageBufferMap, PrettyDuration, Timeout }

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
  @SerialVersionUID(1L) final case object GetCurrentRegions extends ShardRegionQuery with ClusterShardingSerializable

  /**
   * Java API:
   */
  def getCurrentRegionsInstance = GetCurrentRegions

  /**
   * Reply to `GetCurrentRegions`
   */
  @SerialVersionUID(1L) final case class CurrentRegions(regions: Set[Address]) extends ClusterShardingSerializable {

    /**
     * Java API
     */
    def getRegions: java.util.Set[Address] = {
      import akka.util.ccompat.JavaConverters._
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
  @SerialVersionUID(1L) case class GetClusterShardingStats(timeout: FiniteDuration)
      extends ShardRegionQuery
      with ClusterShardingSerializable

  /**
   * Reply to [[GetClusterShardingStats]], contains statistics about all the sharding regions
   * in the cluster.
   */
  @SerialVersionUID(1L) final case class ClusterShardingStats(regions: Map[Address, ShardRegionStats])
      extends ClusterShardingSerializable {

    /**
     * Java API
     */
    def getRegions(): java.util.Map[Address, ShardRegionStats] = {
      import akka.util.ccompat.JavaConverters._
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
  @SerialVersionUID(1L) case object GetShardRegionStats extends ShardRegionQuery with ClusterShardingSerializable

  /**
   * Java API:
   */
  def getRegionStatsInstance = GetShardRegionStats

  /**
   *
   * @param stats the region stats mapping of `ShardId` to number of entities
   * @param failed set of shards if any failed to respond within the timeout
   */
  @SerialVersionUID(1L) final class ShardRegionStats(val stats: Map[ShardId, Int], val failed: Set[ShardId])
      extends ClusterShardingSerializable
      with Product {

    /**
     * Java API
     */
    def getStats(): java.util.Map[ShardId, Int] = {
      import akka.util.ccompat.JavaConverters._
      stats.asJava
    }

    /** Java API */
    def getFailed(): java.util.Set[ShardId] = {
      import akka.util.ccompat.JavaConverters._
      failed.asJava
    }

    // For binary compatibility
    def this(stats: Map[ShardId, Int]) = this(stats, Set.empty[ShardId])
    private[sharding] def copy(stats: Map[ShardId, Int] = stats): ShardRegionStats =
      new ShardRegionStats(stats, this.failed)

    // For binary compatibility: class conversion from case class
    override def equals(other: Any): Boolean = other match {
      case o: ShardRegionStats => o.stats == stats && o.failed == failed
      case _                   => false
    }
    override def hashCode: Int = stats.## + failed.##
    override def toString: String = s"ShardRegionStats[stats=$stats, failed=$failed]"
    override def productArity: Int = 2
    override def productElement(n: Int) =
      if (n == 0) stats else if (n == 1) failed else throw new NoSuchElementException
    override def canEqual(o: Any): Boolean = o.isInstanceOf[ShardRegionStats]

  }
  // For binary compatibility
  object ShardRegionStats extends AbstractFunction1[Map[ShardId, Int], ShardRegionStats] {
    def apply(stats: Map[ShardId, Int]): ShardRegionStats =
      apply(stats, Set.empty[ShardId])
    def apply(stats: Map[ShardId, Int], failed: Set[ShardId]): ShardRegionStats =
      new ShardRegionStats(stats, failed)
    def unapply(stats: ShardRegionStats): Option[Map[ShardId, Int]] =
      Option(stats.stats)
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
      import akka.util.ccompat.JavaConverters._
      shards.asJava
    }
  }

  @SerialVersionUID(1L) final case class ShardState(shardId: ShardId, entityIds: Set[EntityId]) {

    /**
     * Java API:
     */
    def getEntityIds(): java.util.Set[EntityId] = {
      import akka.util.ccompat.JavaConverters._
      entityIds.asJava
    }
  }

  private case object Retry extends ShardRegionCommand

  private case object RegisterRetry extends ShardRegionCommand

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

    def receive: Receive = {
      case ReceiveTimeout =>
        log.warning(
          "HandOffStopMessage[{}] is not handled by some of the entities of the [{}] shard after [{}], " +
          "stopping the remaining [{}] entities.",
          stopMessage.getClass.getName,
          shard,
          handoffTimeout.toCoarsest,
          remaining.size)

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
 * This actor creates children shard actors on demand that it is told to be responsible for.
 * The shard actors in turn create entity actors on demand.
 * It delegates messages targeted to other shards to the responsible
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
    with ActorLogging
    with Timers {

  import ShardingQueries.ShardsQueryResult
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
  var retryCount = 0
  val initRegistrationDelay: FiniteDuration = 100.millis.max(retryInterval / 2 / 2 / 2)
  var nextRegistrationDelay: FiniteDuration = initRegistrationDelay

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
    timers.startTimerWithFixedDelay(Retry, Retry, retryInterval)
    startRegistration()
    logPassivateIdleEntities()
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
    gracefulShutdownProgress.trySuccess(Done)
  }

  private def logPassivateIdleEntities(): Unit = {
    if (settings.shouldPassivateIdleEntities)
      log.info(
        "{}: Idle entities will be passivated after [{}]",
        typeName,
        PrettyDuration.format(settings.passivateIdleEntityAfter))

    if (settings.rememberEntities)
      log.debug("Idle entities will not be passivated because 'rememberEntities' is enabled.")
  }

  // when using proxy the data center can be different from the own data center
  private val targetDcRole = dataCenter match {
    case Some(t) => ClusterSettings.DcRolePrefix + t
    case None    => ClusterSettings.DcRolePrefix + cluster.settings.SelfDataCenter
  }

  def matchingRole(member: Member): Boolean =
    member.hasRole(targetDcRole) && role.forall(member.hasRole)

  def coordinatorSelection: Option[ActorSelection] =
    membersByAge.headOption.map(m => context.actorSelection(RootActorPath(m.address).toString + coordinatorPath))

  /**
   * When leaving the coordinator singleton is started rather quickly on next
   * oldest node and therefore it is good to send the GracefulShutdownReq to
   * the likely locations of the coordinator.
   */
  def gracefulShutdownCoordinatorSelections: List[ActorSelection] =
    membersByAge.take(2).toList.map(m => context.actorSelection(RootActorPath(m.address).toString + coordinatorPath))

  var coordinator: Option[ActorRef] = None

  def changeMembers(newMembers: immutable.SortedSet[Member]): Unit = {
    val before = membersByAge.headOption
    val after = newMembers.headOption
    membersByAge = newMembers
    if (before != after) {
      if (log.isDebugEnabled)
        log.debug(
          "{}: Coordinator moved from [{}] to [{}]",
          typeName,
          before.map(_.address).getOrElse(""),
          after.map(_.address).getOrElse(""))
      coordinator = None
      startRegistration()
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
      log.warning("{}: Message does not have an extractor defined in shard so it was ignored: {}", typeName, unknownMsg)
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
        log.info("{}: Self downed, stopping ShardRegion [{}]", typeName, self.path)
        context.stop(self)
      }

    case _: MemberEvent => // these are expected, no need to warn about them

    case _ => unhandled(evt)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HostShard(shard) =>
      log.debug("{}: Host Shard [{}] ", typeName, shard)
      regionByShard = regionByShard.updated(shard, self)
      regions = regions.updated(self, regions.getOrElse(self, Set.empty) + shard)

      //Start the shard, if already started this does nothing
      getShard(shard)

      sender() ! ShardStarted(shard)

    case ShardHome(shard, shardRegionRef) =>
      log.debug("{}: Shard [{}] located at [{}]", typeName, shard, shardRegionRef)
      regionByShard.get(shard) match {
        case Some(r) if r == self && shardRegionRef != self =>
          // should not happen, inconsistency between ShardRegion and ShardCoordinator
          throw new IllegalStateException(
            s"$typeName: Unexpected change of shard [$shard] from self to [$shardRegionRef]")
        case _ =>
      }
      regionByShard = regionByShard.updated(shard, shardRegionRef)
      regions = regions.updated(shardRegionRef, regions.getOrElse(shardRegionRef, Set.empty) + shard)

      if (shardRegionRef != self)
        context.watch(shardRegionRef)

      if (shardRegionRef == self)
        getShard(shard).foreach(deliverBufferedMessages(shard, _))
      else
        deliverBufferedMessages(shard, shardRegionRef)

    case RegisterAck(coord) =>
      context.watch(coord)
      coordinator = Some(coord)
      finishRegistration()
      requestShardBufferHomes()

    case BeginHandOff(shard) =>
      log.debug("{}: BeginHandOff shard [{}]", typeName, shard)
      if (regionByShard.contains(shard)) {
        val regionRef = regionByShard(shard)
        val updatedShards = regions(regionRef) - shard
        if (updatedShards.isEmpty) regions -= regionRef
        else regions = regions.updated(regionRef, updatedShards)
        regionByShard -= shard
      }
      sender() ! BeginHandOffAck(shard)

    case msg @ HandOff(shard) =>
      log.debug("{}: HandOff shard [{}]", typeName, shard)

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

    case RegisterRetry =>
      if (coordinator.isEmpty) {
        register()
        scheduleNextRegistration()
      }

    case GracefulShutdown =>
      log.debug("{}: Starting graceful shutdown of region and all its shards", typeName)
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

    case msg: GetClusterShardingStats =>
      coordinator.fold(sender ! ClusterShardingStats(Map.empty))(_.forward(msg))

    case GetShardRegionState =>
      replyToRegionStateQuery(sender())

    case GetShardRegionStats =>
      replyToRegionStatsQuery(sender())

    case _ => unhandled(query)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (coordinator.contains(ref)) {
      coordinator = None
      startRegistration()
    } else if (regions.contains(ref)) {
      val shards = regions(ref)
      regionByShard --= shards
      regions -= ref
      if (log.isDebugEnabled)
        log.debug("{}: Region [{}] with shards [{}] terminated", typeName, ref, shards.mkString(", "))
    } else if (shardsByRef.contains(ref)) {
      val shardId: ShardId = shardsByRef(ref)

      shardsByRef = shardsByRef - ref
      shards = shards - shardId
      startingShards -= shardId
      if (handingOff.contains(ref)) {
        handingOff = handingOff - ref
        log.debug("{}: Shard [{}] handoff complete", typeName, shardId)
      } else {
        // if persist fails it will stop
        log.debug("{}: Shard [{}]  terminated while not being handed off", typeName, shardId)
        if (rememberEntities) {
          context.system.scheduler.scheduleOnce(shardFailureBackoff, self, RestartShard(shardId))
        }
      }

      tryCompleteGracefulShutdown()
    }
  }

  def replyToRegionStateQuery(ref: ActorRef): Unit = {
    queryShards[Shard.CurrentShardState](shards, Shard.GetCurrentShardState)
      .map { qr =>
        // Productionize CurrentShardRegionState #27406
        val state =
          qr.responses.map(state => ShardRegion.ShardState(state.shardId, state.entityIds)) ++
          qr.failed.map(sid => ShardRegion.ShardState(sid, Set.empty))
        CurrentShardRegionState(state.toSet)
      }
      .pipeTo(ref)
  }

  def replyToRegionStatsQuery(ref: ActorRef): Unit = {
    queryShards[ShardStats](shards, Shard.GetShardStats)
      .map { qr =>
        ShardRegionStats(qr.responses.map(stats => (stats.shardId, stats.entityCount)).toMap, qr.failed)
      }
      .pipeTo(ref)
  }

  /**
   * Query all or a subset of shards, e.g. unresponsive shards that initially timed out.
   * If the number of `shards` are less than this.shards.size, this could be a retry.
   * Returns a partitioned set of any shards that may have not replied within the
   * timeout and shards that did reply, to provide retry on only that subset.
   *
   * Logs a warning if any of the group timed out.
   *
   * To check subset unresponsive: {{{ queryShards[T](shards.filterKeys(u.contains), shardQuery) }}}
   */
  def queryShards[T: ClassTag](shards: Map[ShardId, ActorRef], msg: Any): Future[ShardsQueryResult[T]] = {
    implicit val timeout: Timeout = settings.shardRegionQueryTimeout

    Future.traverse(shards.toSeq) { case (shardId, shard) => askOne(shard, msg, shardId) }.map { ps =>
      val qr = ShardsQueryResult[T](ps, this.shards.size, timeout.duration)
      if (qr.failed.nonEmpty) log.warning(s"$qr")
      qr
    }
  }

  private def askOne[T: ClassTag](shard: ActorRef, msg: Any, shardId: ShardId)(
      implicit timeout: Timeout): Future[Either[ShardId, T]] =
    (shard ? msg).mapTo[T].transform {
      case Success(t) => Success(Right(t))
      case Failure(_) => Success(Left(shardId))
    }

  private def tryCompleteGracefulShutdown() =
    if (gracefulShutdownInProgress && shards.isEmpty && shardBuffers.isEmpty) {
      context.stop(self) // all shards have been rebalanced, complete graceful shutdown
    }

  def startRegistration(): Unit = {
    nextRegistrationDelay = initRegistrationDelay

    register()
    scheduleNextRegistration()
  }

  def scheduleNextRegistration(): Unit = {
    if (nextRegistrationDelay < retryInterval) {
      timers.startSingleTimer(RegisterRetry, RegisterRetry, nextRegistrationDelay)
      // exponentially increasing retry interval until reaching the normal retryInterval
      nextRegistrationDelay *= 2
    }
  }

  def finishRegistration(): Unit = {
    timers.cancel(RegisterRetry)
  }

  def register(): Unit = {
    coordinatorSelection.foreach(_ ! registrationMessage)
    if (shardBuffers.nonEmpty && retryCount >= 5) coordinatorSelection match {
      case Some(actorSelection) =>
        val coordinatorMessage =
          if (cluster.state.unreachable(membersByAge.head)) s"Coordinator [${membersByAge.head}] is unreachable."
          else s"Coordinator [${membersByAge.head}] is reachable."
        log.warning(
          "{}: Trying to register to coordinator at [{}], but no acknowledgement. Total [{}] buffered messages. [{}]",
          typeName,
          actorSelection,
          shardBuffers.totalSize,
          coordinatorMessage)
      case None =>
        log.warning(
          "{}: No coordinator found to register. Probably, no seed-nodes configured and manual cluster join not performed? Total [{}] buffered messages.",
          typeName,
          shardBuffers.totalSize)
    }
  }

  def registrationMessage: Any =
    if (entityProps.isDefined) Register(self) else RegisterProxy(self)

  def requestShardBufferHomes(): Unit = {
    // Have to use vars because MessageBufferMap has no map, only foreach
    var totalBuffered = 0
    var shards = List.empty[String]
    shardBuffers.foreach {
      case (shard, buf) =>
        coordinator.foreach { c =>
          totalBuffered += buf.size
          shards ::= shard
          log.debug(
            "{}: Retry request for shard [{}] homes from coordinator at [{}]. [{}] buffered messages.",
            typeName,
            shard,
            c,
            buf.size)
          c ! GetShardHome(shard)
        }
    }

    if (retryCount >= 5 && retryCount % 5 == 0 && log.isWarningEnabled) {
      log.warning(
        "{}: Retry request for shards [{}] homes from coordinator. [{}] total buffered messages.",
        typeName,
        shards.sorted.mkString(","),
        totalBuffered)
    }
  }

  def initializeShard(id: ShardId, shard: ActorRef): Unit = {
    log.debug("{}: Shard was initialized {}", typeName, id)
    startingShards -= id
    deliverBufferedMessages(id, shard)
  }

  def bufferMessage(shardId: ShardId, msg: Any, snd: ActorRef) = {
    val totBufSize = shardBuffers.totalSize
    if (totBufSize >= bufferSize) {
      if (loggedFullBufferWarning)
        log.debug("{}: Buffer is full, dropping message for shard [{}]", typeName, shardId)
      else {
        log.warning("{}: Buffer is full, dropping message for shard [{}]", typeName, shardId)
        loggedFullBufferWarning = true
      }
      context.system.deadLetters ! msg
    } else {
      shardBuffers.append(shardId, msg, snd)

      // log some insight to how buffers are filled up every 10% of the buffer capacity
      val tot = totBufSize + 1
      if (tot % (bufferSize / 10) == 0) {
        val logMsg = s"$typeName: ShardRegion is using [${100.0 * tot / bufferSize} %] of its buffer capacity."
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
      log.debug("{}: Deliver [{}] buffered messages for shard [{}]", typeName, buf.size, shardId)
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
        log.error(
          ex,
          "{}: When using remember-entities the shard id extractor must handle ShardRegion.StartEntity(id).",
          typeName)
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
              log.debug("{}: Request shard [{}] home. Coordinator [{}]", typeName, shardId, coordinator)
              coordinator.foreach(_ ! GetShardHome(shardId))
            }
            val buf = shardBuffers.getOrEmpty(shardId)
            log.debug(
              "{}: Buffer message for shard [{}]. Total [{}] buffered messages.",
              typeName,
              shardId,
              buf.size + 1)
            shardBuffers.append(shardId, msg, snd)
        }

      case _ =>
        val shardId = extractShardId(msg)
        regionByShard.get(shardId) match {
          case Some(shardRegionRef) if shardRegionRef == self =>
            getShard(shardId) match {
              case Some(shard) =>
                if (shardBuffers.contains(shardId)) {
                  // Since now messages to a shard is buffered then those messages must be in right order
                  bufferMessage(shardId, msg, snd)
                  deliverBufferedMessages(shardId, shard)
                } else shard.tell(msg, snd)
              case None => bufferMessage(shardId, msg, snd)
            }
          case Some(shardRegionRef) =>
            log.debug("{}: Forwarding message for shard [{}] to [{}]", typeName, shardId, shardRegionRef)
            shardRegionRef.tell(msg, snd)
          case None if shardId == null || shardId == "" =>
            log.warning("{}: Shard must not be empty, dropping message [{}]", typeName, msg.getClass.getName)
            context.system.deadLetters ! msg
          case None =>
            if (!shardBuffers.contains(shardId)) {
              log.debug("{}: Request shard [{}] home. Coordinator [{}]", typeName, shardId, coordinator)
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
            log.debug("{}: Starting shard [{}] in region", typeName, id)

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
          case Some(_) =>
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
