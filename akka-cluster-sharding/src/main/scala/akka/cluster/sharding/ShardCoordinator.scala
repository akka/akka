/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.util.Timeout

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import akka.actor._
import akka.actor.DeadLetterSuppression
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.ddata.LWWRegister
import akka.cluster.ddata.LWWRegisterKey
import akka.cluster.ddata.Replicator._
import akka.dispatch.ExecutionContexts
import akka.pattern.{ AskTimeoutException, pipe }
import akka.persistence._
import akka.cluster.ClusterEvent
import akka.cluster.ddata.GSet
import akka.cluster.ddata.GSetKey
import akka.cluster.ddata.Key
import akka.cluster.ddata.ReplicatedData

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardCoordinator {

  import ShardRegion.ShardId

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardCoordinator]] actor.
   */
  private[akka] def props(typeName: String, settings: ClusterShardingSettings,
                          allocationStrategy: ShardAllocationStrategy): Props =
    Props(new PersistentShardCoordinator(typeName: String, settings, allocationStrategy)).withDeploy(Deploy.local)

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardCoordinator]] actor with state based on ddata.
   */
  private[akka] def props(typeName: String, settings: ClusterShardingSettings,
                          allocationStrategy: ShardAllocationStrategy,
                          replicator:         ActorRef, majorityMinCap: Int): Props =
    Props(new DDataShardCoordinator(typeName: String, settings, allocationStrategy, replicator,
      majorityMinCap, settings.rememberEntities)).withDeploy(Deploy.local)

  /**
   * Interface of the pluggable shard allocation and rebalancing logic used by the [[ShardCoordinator]].
   *
   * Java implementations should extend [[AbstractShardAllocationStrategy]].
   */
  trait ShardAllocationStrategy extends NoSerializationVerificationNeeded {
    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *                  shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *                                in the order they were allocated
     * @return a `Future` of the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *         the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: ShardId,
                      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef]

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *                                in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *                            you should not include these in the returned set
     * @return a `Future` of the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress:     Set[ShardId]): Future[Set[ShardId]]
  }

  /**
   * Java API: Java implementations of custom shard allocation and rebalancing logic used by the [[ShardCoordinator]]
   * should extend this abstract class and implement the two methods.
   */
  abstract class AbstractShardAllocationStrategy extends ShardAllocationStrategy {
    override final def allocateShard(requester: ActorRef, shardId: ShardId,
                                     currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {

      import scala.collection.JavaConverters._
      allocateShard(requester, shardId, currentShardAllocations.asJava)
    }

    override final def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress:     Set[ShardId]): Future[Set[ShardId]] = {
      import scala.collection.JavaConverters._
      implicit val ec = ExecutionContexts.sameThreadExecutionContext
      rebalance(currentShardAllocations.asJava, rebalanceInProgress.asJava).map(_.asScala.toSet)
    }

    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *                  shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *                                in the order they were allocated
     * @return a `Future` of the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *         the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: String,
                      currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]]): Future[ActorRef]

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *                                in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *                            you should not include these in the returned set
     * @return a `Future` of the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(
      currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]],
      rebalanceInProgress:     java.util.Set[String]): Future[java.util.Set[String]]
  }

  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])

  /**
   * The default implementation of [[ShardCoordinator.LeastShardAllocationStrategy]]
   * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
   *
   * When a node is removed from the cluster the shards on that node will be started on the remaining nodes,
   * evenly spread on the remaining nodes (by picking regions with least shards).
   *
   * When a node is added to the cluster the shards on the existing nodes will be rebalanced to the new node.
   * It picks shards for rebalancing from the `ShardRegion` with most number of previously allocated shards.
   * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
   * i.e. new members in the cluster. There is a configurable threshold of how large the difference
   * must be to begin the rebalancing. The difference between number of shards in the region with most shards and
   * the region with least shards must be greater than the `rebalanceThreshold` for the rebalance to occur.
   *
   * A `rebalanceThreshold` of 1 gives the best distribution and therefore typically the best choice.
   * A higher threshold means that more shards can be rebalanced at the same time instead of one-by-one.
   * That has the advantage that the rebalance process can be quicker but has the drawback that the
   * the number of shards (and therefore load) between different nodes may be significantly different.
   * Given the recommendation of using 10x shards than number of nodes and `rebalanceThreshold=10` can result
   * in one node hosting ~2 times the number of shards of other nodes. Example: 1000 shards on 100 nodes means
   * 10 shards per node. One node may have 19 shards and others 10 without a rebalance occurring.
   *
   * The number of ongoing rebalancing processes can be limited by `maxSimultaneousRebalance`.
   */
  @SerialVersionUID(1L)
  class LeastShardAllocationStrategy(rebalanceThreshold: Int, maxSimultaneousRebalance: Int)
    extends ShardAllocationStrategy with Serializable {

    override def allocateShard(requester: ActorRef, shardId: ShardId,
                               currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {
      val (regionWithLeastShards, _) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
      Future.successful(regionWithLeastShards)
    }

    override def rebalance(
      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
      rebalanceInProgress:     Set[ShardId]): Future[Set[ShardId]] = {
      if (rebalanceInProgress.size < maxSimultaneousRebalance) {
        val (regionWithLeastShards, leastShards) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
        val mostShards = currentShardAllocations.collect {
          case (_, v) ⇒ v.filterNot(s ⇒ rebalanceInProgress(s))
        }.maxBy(_.size)
        val difference = mostShards.size - leastShards.size
        if (difference > rebalanceThreshold) {
          val n = math.min(
            math.min(difference - rebalanceThreshold, rebalanceThreshold),
            maxSimultaneousRebalance - rebalanceInProgress.size)
          Future.successful(mostShards.sorted.take(n).toSet)
        } else
          emptyRebalanceResult
      } else emptyRebalanceResult
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    /**
     * Messages sent to the coordinator
     */
    sealed trait CoordinatorCommand extends ClusterShardingSerializable
    /**
     * Messages sent from the coordinator
     */
    sealed trait CoordinatorMessage extends ClusterShardingSerializable
    /**
     * `ShardRegion` registers to `ShardCoordinator`, until it receives [[RegisterAck]].
     */
    @SerialVersionUID(1L) final case class Register(shardRegion: ActorRef) extends CoordinatorCommand
      with DeadLetterSuppression

    /**
     * `ShardRegion` in proxy only mode registers to `ShardCoordinator`, until it receives [[RegisterAck]].
     */
    @SerialVersionUID(1L) final case class RegisterProxy(shardRegionProxy: ActorRef) extends CoordinatorCommand
      with DeadLetterSuppression

    /**
     * Acknowledgement from `ShardCoordinator` that [[Register]] or [[RegisterProxy]] was successful.
     */
    @SerialVersionUID(1L) final case class RegisterAck(coordinator: ActorRef) extends CoordinatorMessage
    /**
     * `ShardRegion` requests the location of a shard by sending this message
     * to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class GetShardHome(shard: ShardId) extends CoordinatorCommand
      with DeadLetterSuppression

    /**
     * `ShardCoordinator` replies with this message for [[GetShardHome]] requests.
     */
    @SerialVersionUID(1L) final case class ShardHome(shard: ShardId, ref: ActorRef) extends CoordinatorMessage
    /**
     * `ShardCoordinator` informs a `ShardRegion` that it is hosting this shard
     */
    @SerialVersionUID(1L) final case class HostShard(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardRegion` replies with this message for [[HostShard]] requests which lead to it hosting the shard
     */
    @SerialVersionUID(1l) final case class ShardStarted(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardCoordinator` initiates rebalancing process by sending this message
     * to all registered `ShardRegion` actors (including proxy only). They are
     * supposed to discard their known location of the shard, i.e. start buffering
     * incoming messages for the shard. They reply with [[BeginHandOffAck]].
     * When all have replied the `ShardCoordinator` continues by sending
     * `HandOff` to the `ShardRegion` responsible for the shard.
     */
    @SerialVersionUID(1L) final case class BeginHandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Acknowledgement of [[BeginHandOff]]
     */
    @SerialVersionUID(1L) final case class BeginHandOffAck(shard: ShardId) extends CoordinatorCommand
    /**
     * When all `ShardRegion` actors have acknowledged the `BeginHandOff` the
     * `ShardCoordinator` sends this message to the `ShardRegion` responsible for the
     * shard. The `ShardRegion` is supposed to stop all entities in that shard and when
     * all entities have terminated reply with `ShardStopped` to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class HandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Reply to `HandOff` when all entities in the shard have been terminated.
     */
    @SerialVersionUID(1L) final case class ShardStopped(shard: ShardId) extends CoordinatorCommand

    /**
     * `ShardRegion` requests full handoff to be able to shutdown gracefully.
     */
    @SerialVersionUID(1L) final case class GracefulShutdownReq(shardRegion: ActorRef) extends CoordinatorCommand
      with DeadLetterSuppression

    // DomainEvents for the persistent state of the event sourced ShardCoordinator
    sealed trait DomainEvent extends ClusterShardingSerializable
    @SerialVersionUID(1L) final case class ShardRegionRegistered(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyRegistered(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionTerminated(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyTerminated(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeAllocated(shard: ShardId, region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeDeallocated(shard: ShardId) extends DomainEvent

    case object StateInitialized

    object State {
      val empty = State()
    }

    /**
     * Persistent state of the event sourced ShardCoordinator.
     */
    @SerialVersionUID(1L) final case class State private[akka] (
      // region for each shard
      shards: Map[ShardId, ActorRef] = Map.empty,
      // shards for each region
      regions:           Map[ActorRef, Vector[ShardId]] = Map.empty,
      regionProxies:     Set[ActorRef]                  = Set.empty,
      unallocatedShards: Set[ShardId]                   = Set.empty,
      rememberEntities:  Boolean                        = false) extends ClusterShardingSerializable {

      def withRememberEntities(enabled: Boolean): State = {
        if (enabled)
          copy(rememberEntities = enabled)
        else
          copy(unallocatedShards = Set.empty, rememberEntities = enabled)
      }

      def isEmpty: Boolean =
        shards.isEmpty && regions.isEmpty && regionProxies.isEmpty

      def allShards: Set[ShardId] = shards.keySet union unallocatedShards

      def updated(event: DomainEvent): State = event match {
        case ShardRegionRegistered(region) ⇒
          require(!regions.contains(region), s"Region $region already registered: $this")
          copy(regions = regions.updated(region, Vector.empty))
        case ShardRegionProxyRegistered(proxy) ⇒
          require(!regionProxies.contains(proxy), s"Region proxy $proxy already registered: $this")
          copy(regionProxies = regionProxies + proxy)
        case ShardRegionTerminated(region) ⇒
          require(regions.contains(region), s"Terminated region $region not registered: $this")
          val newUnallocatedShards =
            if (rememberEntities) (unallocatedShards ++ regions(region)) else unallocatedShards
          copy(
            regions = regions - region,
            shards = shards -- regions(region),
            unallocatedShards = newUnallocatedShards)
        case ShardRegionProxyTerminated(proxy) ⇒
          require(regionProxies.contains(proxy), s"Terminated region proxy $proxy not registered: $this")
          copy(regionProxies = regionProxies - proxy)
        case ShardHomeAllocated(shard, region) ⇒
          require(regions.contains(region), s"Region $region not registered: $this")
          require(!shards.contains(shard), s"Shard [$shard] already allocated: $this")
          val newUnallocatedShards =
            if (rememberEntities) (unallocatedShards - shard) else unallocatedShards
          copy(
            shards = shards.updated(shard, region),
            regions = regions.updated(region, regions(region) :+ shard),
            unallocatedShards = newUnallocatedShards)
        case ShardHomeDeallocated(shard) ⇒
          require(shards.contains(shard), s"Shard [$shard] not allocated: $this")
          val region = shards(shard)
          require(regions.contains(region), s"Region $region for shard [$shard] not registered: $this")
          val newUnallocatedShards =
            if (rememberEntities) (unallocatedShards + shard) else unallocatedShards
          copy(
            shards = shards - shard,
            regions = regions.updated(region, regions(region).filterNot(_ == shard)),
            unallocatedShards = newUnallocatedShards)
      }
    }

  }

  /**
   * Periodic message to trigger rebalance
   */
  private case object RebalanceTick
  /**
   * End of rebalance process performed by [[RebalanceWorker]]
   */
  private final case class RebalanceDone(shard: ShardId, ok: Boolean)
  /**
   * Check if we've received a shard start request
   */
  private final case class ResendShardHost(shard: ShardId, region: ActorRef)

  private final case class DelayedShardRegionTerminated(region: ActorRef)

  /**
   * Result of `allocateShard` is piped to self with this message.
   */
  private final case class AllocateShardResult(
    shard: ShardId, shardRegion: Option[ActorRef], getShardHomeSender: ActorRef)

  /**
   * Result of `rebalance` is piped to self with this message.
   */
  private final case class RebalanceResult(shards: Set[ShardId])

  /**
   * INTERNAL API. Rebalancing process is performed by this actor.
   * It sends `BeginHandOff` to all `ShardRegion` actors followed by
   * `HandOff` to the `ShardRegion` responsible for the shard.
   * When the handoff is completed it sends [[akka.cluster.sharding.RebalanceDone]] to its
   * parent `ShardCoordinator`. If the process takes longer than the
   * `handOffTimeout` it also sends [[akka.cluster.sharding.RebalanceDone]].
   */
  private[akka] class RebalanceWorker(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                      regions: Set[ActorRef]) extends Actor {
    import Internal._
    regions.foreach(_ ! BeginHandOff(shard))
    var remaining = regions

    import context.dispatcher
    context.system.scheduler.scheduleOnce(handOffTimeout, self, ReceiveTimeout)

    def receive = {
      case BeginHandOffAck(`shard`) ⇒
        remaining -= sender()
        if (remaining.isEmpty) {
          from ! HandOff(shard)
          context.become(stoppingShard, discardOld = true)
        }
      case ReceiveTimeout ⇒ done(ok = false)
    }

    def stoppingShard: Receive = {
      case ShardStopped(`shard`) ⇒ done(ok = true)
      case ReceiveTimeout        ⇒ done(ok = false)
    }

    def done(ok: Boolean): Unit = {
      context.parent ! RebalanceDone(shard, ok)
      context.stop(self)
    }
  }

  private[akka] def rebalanceWorkerProps(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                         regions: Set[ActorRef]): Props =
    Props(new RebalanceWorker(shard, from, handOffTimeout, regions))

}

/**
 * Singleton coordinator that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
abstract class ShardCoordinator(typeName: String, settings: ClusterShardingSettings,
                                allocationStrategy: ShardCoordinator.ShardAllocationStrategy)
  extends Actor with ActorLogging {
  import ShardCoordinator._
  import ShardCoordinator.Internal._
  import ShardRegion.ShardId
  import settings.tuningParameters._

  val cluster = Cluster(context.system)
  val removalMargin = cluster.downingProvider.downRemovalMargin
  val minMembers = settings.role match {
    case None ⇒
      cluster.settings.MinNrOfMembers
    case Some(r) ⇒
      cluster.settings.MinNrOfMembersOfRole.getOrElse(r, cluster.settings.MinNrOfMembers)
  }
  var allRegionsRegistered = false

  var state = State.empty.withRememberEntities(settings.rememberEntities)
  // rebalanceInProgress for the ShardId keys, pending GetShardHome requests by the ActorRef values
  var rebalanceInProgress = Map.empty[ShardId, Set[ActorRef]]
  var unAckedHostShards = Map.empty[ShardId, Cancellable]
  // regions that have requested handoff, for graceful shutdown
  var gracefulShutdownInProgress = Set.empty[ActorRef]
  var aliveRegions = Set.empty[ActorRef]
  var regionTerminationInProgress = Set.empty[ActorRef]

  import context.dispatcher
  val rebalanceTask = context.system.scheduler.schedule(rebalanceInterval, rebalanceInterval, self, RebalanceTick)

  cluster.subscribe(self, initialStateMode = InitialStateAsEvents, ClusterShuttingDown.getClass)

  override def postStop(): Unit = {
    super.postStop()
    rebalanceTask.cancel()
    cluster.unsubscribe(self)
  }

  def isMember(region: ActorRef): Boolean = {
    val regionAddress = region.path.address
    (region.path.address == self.path.address ||
      cluster.state.members.exists(m ⇒ m.address == regionAddress && m.status == MemberStatus.Up))
  }

  def active: Receive = ({
    case Register(region) ⇒
      if (isMember(region)) {
        log.debug("ShardRegion registered: [{}]", region)
        aliveRegions += region
        if (state.regions.contains(region)) {
          region ! RegisterAck(self)
          allocateShardHomesForRememberEntities()
        } else {
          gracefulShutdownInProgress -= region
          update(ShardRegionRegistered(region)) { evt ⇒
            state = state.updated(evt)
            context.watch(region)
            region ! RegisterAck(self)
            allocateShardHomesForRememberEntities()
          }
        }
      } else {
        log.debug("ShardRegion {} was not registered since the coordinator currently does not know about a node of that region", region)
      }

    case RegisterProxy(proxy) ⇒
      log.debug("ShardRegion proxy registered: [{}]", proxy)
      if (state.regionProxies.contains(proxy))
        proxy ! RegisterAck(self)
      else {
        update(ShardRegionProxyRegistered(proxy)) { evt ⇒
          state = state.updated(evt)
          context.watch(proxy)
          proxy ! RegisterAck(self)
        }
      }

    case GetShardHome(shard) ⇒
      if (!handleGetShardHome(shard)) {
        // location not know, yet
        val activeRegions = state.regions -- gracefulShutdownInProgress
        if (activeRegions.nonEmpty) {
          val getShardHomeSender = sender()
          val regionFuture = allocationStrategy.allocateShard(getShardHomeSender, shard, activeRegions)
          regionFuture.value match {
            case Some(Success(region)) ⇒
              continueGetShardHome(shard, region, getShardHomeSender)
            case _ ⇒
              // continue when future is completed
              regionFuture.map { region ⇒
                AllocateShardResult(shard, Some(region), getShardHomeSender)
              }.recover {
                case _ ⇒ AllocateShardResult(shard, None, getShardHomeSender)
              }.pipeTo(self)
          }
        }
      }

    case AllocateShardResult(shard, None, getShardHomeSender) ⇒
      log.debug("Shard [{}] allocation failed. It will be retried.", shard)

    case AllocateShardResult(shard, Some(region), getShardHomeSender) ⇒
      continueGetShardHome(shard, region, getShardHomeSender)

    case ShardStarted(shard) ⇒
      unAckedHostShards.get(shard) match {
        case Some(cancel) ⇒
          cancel.cancel()
          unAckedHostShards = unAckedHostShards - shard
        case _ ⇒
      }

    case ResendShardHost(shard, region) ⇒
      state.shards.get(shard) match {
        case Some(`region`) ⇒ sendHostShardMsg(shard, region)
        case _              ⇒ //Reallocated to another region
      }

    case RebalanceTick ⇒
      if (state.regions.nonEmpty) {
        val shardsFuture = allocationStrategy.rebalance(state.regions, rebalanceInProgress.keySet)
        shardsFuture.value match {
          case Some(Success(shards)) ⇒
            continueRebalance(shards)
          case _ ⇒
            // continue when future is completed
            shardsFuture.map { shards ⇒ RebalanceResult(shards)
            }.recover {
              case _ ⇒ RebalanceResult(Set.empty)
            }.pipeTo(self)
        }
      }

    case RebalanceResult(shards) ⇒
      continueRebalance(shards)

    case RebalanceDone(shard, ok) ⇒
      log.debug("Rebalance shard [{}] done [{}]", shard, ok)
      // The shard could have been removed by ShardRegionTerminated
      if (state.shards.contains(shard)) {
        if (ok) {
          update(ShardHomeDeallocated(shard)) { evt ⇒
            log.debug("Shard [{}] deallocated after rebalance", shard)
            state = state.updated(evt)
            clearRebalanceInProgress(shard)
            allocateShardHomesForRememberEntities()
          }
        } else {
          // rebalance not completed, graceful shutdown will be retried
          gracefulShutdownInProgress -= state.shards(shard)
          clearRebalanceInProgress(shard)
        }
      } else {
        clearRebalanceInProgress(shard)
      }

    case GracefulShutdownReq(region) ⇒
      if (!gracefulShutdownInProgress(region))
        state.regions.get(region) match {
          case Some(shards) ⇒
            log.debug("Graceful shutdown of region [{}] with shards [{}]", region, shards)
            gracefulShutdownInProgress += region
            continueRebalance(shards.toSet)
          case None ⇒
        }

    case ShardRegion.GetClusterShardingStats(waitMax) ⇒
      import akka.pattern.ask
      implicit val timeout: Timeout = waitMax
      Future.sequence(aliveRegions.map { regionActor ⇒
        (regionActor ? ShardRegion.GetShardRegionStats).mapTo[ShardRegion.ShardRegionStats]
          .map(stats ⇒ regionActor → stats)
      }).map { allRegionStats ⇒
        ShardRegion.ClusterShardingStats(allRegionStats.map {
          case (region, stats) ⇒
            val regionAddress = region.path.address
            val address: Address =
              if (regionAddress.hasLocalScope && regionAddress.system == cluster.selfAddress.system) cluster.selfAddress
              else regionAddress

            address → stats
        }.toMap)
      }.recover {
        case x: AskTimeoutException ⇒ ShardRegion.ClusterShardingStats(Map.empty)
      }.pipeTo(sender())

    case ShardHome(_, _) ⇒
    //On rebalance, we send ourselves a GetShardHome message to reallocate a
    // shard. This receive handles the "response" from that message. i.e. ignores it.

    case ClusterShuttingDown ⇒
      log.debug("Shutting down ShardCoordinator")
      // can't stop because supervisor will start it again,
      // it will soon be stopped when singleton is stopped
      context.become(shuttingDown)

    case ShardRegion.GetCurrentRegions ⇒
      val reply = ShardRegion.CurrentRegions(state.regions.keySet.map { ref ⇒
        if (ref.path.address.host.isEmpty) cluster.selfAddress
        else ref.path.address
      })
      sender() ! reply

  }: Receive).orElse[Any, Unit](receiveTerminated)

  private def clearRebalanceInProgress(shard: String): Unit = {
    rebalanceInProgress.get(shard) match {
      case Some(pendingGetShardHome) ⇒
        val msg = GetShardHome(shard)
        pendingGetShardHome.foreach { getShardHomeSender ⇒
          self.tell(msg, getShardHomeSender)
        }
        rebalanceInProgress -= shard
      case None ⇒
    }
  }

  private def deferGetShardHomeRequest(shard: ShardId, from: ActorRef): Unit = {
    log.debug("GetShardHome [{}] request from [{}] deferred, because rebalance is in progress for this shard. " +
      "It will be handled when rebalance is done.", shard, from)
    rebalanceInProgress = rebalanceInProgress.updated(shard, rebalanceInProgress(shard) + from)
  }

  /**
   * @return `true` if the message could be handled without state update, i.e.
   *         the shard location was known or the request was deferred or ignored
   */
  def handleGetShardHome(shard: ShardId): Boolean = {
    if (rebalanceInProgress.contains(shard)) {
      deferGetShardHomeRequest(shard, sender())
      true
    } else if (!hasAllRegionsRegistered()) {
      log.debug("GetShardHome [{}] request ignored, because not all regions have registered yet.", shard)
      true
    } else {
      state.shards.get(shard) match {
        case Some(ref) ⇒
          if (regionTerminationInProgress(ref))
            log.debug("GetShardHome [{}] request ignored, due to region [{}] termination in progress.", shard, ref)
          else
            sender() ! ShardHome(shard, ref)
          true
        case None ⇒
          false // location not known, yet, caller will handle allocation
      }
    }
  }

  def receiveTerminated: Receive = {
    case t @ Terminated(ref) ⇒
      if (state.regions.contains(ref)) {
        if (removalMargin != Duration.Zero && t.addressTerminated && aliveRegions(ref)) {
          context.system.scheduler.scheduleOnce(removalMargin, self, DelayedShardRegionTerminated(ref))
          regionTerminationInProgress += ref
        } else
          regionTerminated(ref)
      } else if (state.regionProxies.contains(ref)) {
        regionProxyTerminated(ref)
      }

    case DelayedShardRegionTerminated(ref) ⇒
      regionTerminated(ref)
  }

  def update[E <: DomainEvent](evt: E)(f: E ⇒ Unit): Unit

  def watchStateActors(): Unit = {

    // Optimization:
    // Consider regions that don't belong to the current cluster to be terminated.
    // This is an optimization that makes it operational faster and reduces the
    // amount of lost messages during startup.
    val nodes = cluster.state.members.map(_.address)
    state.regions.foreach {
      case (ref, _) ⇒
        val a = ref.path.address
        if (a.hasLocalScope || nodes(a))
          context.watch(ref)
        else
          regionTerminated(ref) // not part of cluster
    }
    state.regionProxies.foreach { ref ⇒
      val a = ref.path.address
      if (a.hasLocalScope || nodes(a))
        context.watch(ref)
      else
        regionProxyTerminated(ref) // not part of cluster
    }

    // Let the quick (those not involving failure detection) Terminated messages
    // be processed before starting to reply to GetShardHome.
    // This is an optimization that makes it operational faster and reduces the
    // amount of lost messages during startup.
    context.system.scheduler.scheduleOnce(500.millis, self, StateInitialized)
  }

  def stateInitialized(): Unit = {
    state.shards.foreach { case (a, r) ⇒ sendHostShardMsg(a, r) }
    allocateShardHomesForRememberEntities()
  }

  def hasAllRegionsRegistered(): Boolean = {
    // the check is only for startup, i.e. once all have registered we don't check more
    if (allRegionsRegistered) true
    else {
      allRegionsRegistered = aliveRegions.size >= minMembers
      allRegionsRegistered
    }
  }

  def regionTerminated(ref: ActorRef): Unit =
    if (state.regions.contains(ref)) {
      log.debug("ShardRegion terminated: [{}]", ref)
      regionTerminationInProgress += ref
      state.regions(ref).foreach { s ⇒ self ! GetShardHome(s) }

      update(ShardRegionTerminated(ref)) { evt ⇒
        state = state.updated(evt)
        gracefulShutdownInProgress -= ref
        regionTerminationInProgress -= ref
        aliveRegions -= ref
        allocateShardHomesForRememberEntities()
      }
    }

  def regionProxyTerminated(ref: ActorRef): Unit =
    if (state.regionProxies.contains(ref)) {
      log.debug("ShardRegion proxy terminated: [{}]", ref)
      update(ShardRegionProxyTerminated(ref)) { evt ⇒
        state = state.updated(evt)
      }
    }

  def shuttingDown: Receive = {
    case _ ⇒ // ignore all
  }

  def sendHostShardMsg(shard: ShardId, region: ActorRef): Unit = {
    region ! HostShard(shard)
    val cancel = context.system.scheduler.scheduleOnce(shardStartTimeout, self, ResendShardHost(shard, region))
    unAckedHostShards = unAckedHostShards.updated(shard, cancel)
  }

  def allocateShardHomesForRememberEntities(): Unit = {
    if (settings.rememberEntities && state.unallocatedShards.nonEmpty)
      state.unallocatedShards.foreach { self ! GetShardHome(_) }
  }

  def continueGetShardHome(shard: ShardId, region: ActorRef, getShardHomeSender: ActorRef): Unit =
    if (rebalanceInProgress.contains(shard)) {
      deferGetShardHomeRequest(shard, getShardHomeSender)
    } else {
      state.shards.get(shard) match {
        case Some(ref) ⇒ getShardHomeSender ! ShardHome(shard, ref)
        case None ⇒
          if (state.regions.contains(region) && !gracefulShutdownInProgress.contains(region)) {
            update(ShardHomeAllocated(shard, region)) { evt ⇒
              state = state.updated(evt)
              log.debug("Shard [{}] allocated at [{}]", evt.shard, evt.region)

              sendHostShardMsg(evt.shard, evt.region)
              getShardHomeSender ! ShardHome(evt.shard, evt.region)
            }
          } else
            log.debug(
              "Allocated region {} for shard [{}] is not (any longer) one of the registered regions: {}",
              region, shard, state)
      }
    }

  def continueRebalance(shards: Set[ShardId]): Unit =
    shards.foreach { shard ⇒
      if (!rebalanceInProgress.contains(shard)) {
        state.shards.get(shard) match {
          case Some(rebalanceFromRegion) ⇒
            rebalanceInProgress = rebalanceInProgress.updated(shard, Set.empty)
            log.debug("Rebalance shard [{}] from [{}]", shard, rebalanceFromRegion)
            context.actorOf(rebalanceWorkerProps(shard, rebalanceFromRegion, handOffTimeout,
              state.regions.keySet union state.regionProxies)
              .withDispatcher(context.props.dispatcher))
          case None ⇒
            log.debug("Rebalance of non-existing shard [{}] is ignored", shard)
        }

      }
    }

}

/**
 * Singleton coordinator that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class PersistentShardCoordinator(typeName: String, settings: ClusterShardingSettings,
                                 allocationStrategy: ShardCoordinator.ShardAllocationStrategy)
  extends ShardCoordinator(typeName, settings, allocationStrategy) with PersistentActor {
  import ShardCoordinator.Internal._
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Coordinator"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case evt: DomainEvent ⇒
      log.debug("receiveRecover {}", evt)
      evt match {
        case ShardRegionRegistered(region) ⇒
          state = state.updated(evt)
        case ShardRegionProxyRegistered(proxy) ⇒
          state = state.updated(evt)
        case ShardRegionTerminated(region) ⇒
          if (state.regions.contains(region))
            state = state.updated(evt)
          else {
            log.debug("ShardRegionTerminated, but region {} was not registered. This inconsistency is due to that " +
              " some stored ActorRef in Akka v2.3.0 and v2.3.1 did not contain full address information. It will be " +
              "removed by later watch.", region)
          }
        case ShardRegionProxyTerminated(proxy) ⇒
          if (state.regionProxies.contains(proxy))
            state = state.updated(evt)
        case ShardHomeAllocated(shard, region) ⇒
          state = state.updated(evt)
        case _: ShardHomeDeallocated ⇒
          state = state.updated(evt)
      }

    case SnapshotOffer(_, st: State) ⇒
      log.debug("receiveRecover SnapshotOffer {}", st)
      state = st.withRememberEntities(settings.rememberEntities)
      //Old versions of the state object may not have unallocatedShard set,
      // thus it will be null.
      if (state.unallocatedShards == null)
        state = state.copy(unallocatedShards = Set.empty)

    case RecoveryCompleted ⇒
      state = state.withRememberEntities(settings.rememberEntities)
      watchStateActors()
  }

  override def receiveCommand: Receive = waitingForStateInitialized

  def waitingForStateInitialized: Receive = ({
    case StateInitialized ⇒
      stateInitialized()
      context.become(active.orElse[Any, Unit](receiveSnapshotResult))

  }: Receive).orElse[Any, Unit](receiveTerminated).orElse[Any, Unit](receiveSnapshotResult)

  def receiveSnapshotResult: Receive = {
    case SaveSnapshotSuccess(m) ⇒
      log.debug("Persistent snapshot saved successfully")
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
      log.warning("Persistent snapshot failure: {}", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) ⇒
      log.debug("Persistent messages to {} deleted successfully", toSequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = toSequenceNr - 1))

    case DeleteMessagesFailure(reason, toSequenceNr) ⇒
      log.warning("Persistent messages to {} deletion failure: {}", toSequenceNr, reason.getMessage)

    case DeleteSnapshotsSuccess(m) ⇒
      log.debug("Persistent snapshots matching {} deleted successfully", m)

    case DeleteSnapshotsFailure(m, reason) ⇒
      log.warning("Persistent snapshots matching {} deletion failure: {}", m, reason.getMessage)
  }

  def update[E <: DomainEvent](evt: E)(f: E ⇒ Unit): Unit = {
    saveSnapshotWhenNeeded()
    persist(evt)(f)
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }
}

/**
 * Singleton coordinator (with state based on ddata) that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class DDataShardCoordinator(typeName: String, settings: ClusterShardingSettings,
                            allocationStrategy: ShardCoordinator.ShardAllocationStrategy,
                            replicator:         ActorRef,
                            majorityMinCap:     Int,
                            rememberEntities:   Boolean)
  extends ShardCoordinator(typeName, settings, allocationStrategy) with Stash {
  import ShardCoordinator.Internal._
  import akka.cluster.ddata.Replicator.Update

  private val readMajority = ReadMajority(
    settings.tuningParameters.waitingForStateTimeout,
    majorityMinCap)
  private val writeMajority = WriteMajority(settings.tuningParameters.updatingStateTimeout, majorityMinCap)

  implicit val node = Cluster(context.system)
  val CoordinatorStateKey = LWWRegisterKey[State](s"${typeName}CoordinatorState")
  val initEmptyState = State.empty.withRememberEntities(settings.rememberEntities)

  val AllShardsKey = GSetKey[String](s"shard-${typeName}-all")
  val allKeys: Set[Key[ReplicatedData]] =
    if (rememberEntities) Set(CoordinatorStateKey, AllShardsKey) else Set(CoordinatorStateKey)

  var shards = Set.empty[String]
  if (rememberEntities)
    replicator ! Subscribe(AllShardsKey, self)

  node.subscribe(self, ClusterEvent.InitialStateAsEvents, ClusterShuttingDown.getClass)

  // get state from ddata replicator, repeat until GetSuccess
  getCoordinatorState()
  getAllShards()

  override def receive: Receive = waitingForState(allKeys)

  // This state will drop all other messages since they will be retried
  def waitingForState(remainingKeys: Set[Key[ReplicatedData]]): Receive = ({
    case g @ GetSuccess(CoordinatorStateKey, _) ⇒
      state = g.get(CoordinatorStateKey).value.withRememberEntities(settings.rememberEntities)
      val newRemainingKeys = remainingKeys - CoordinatorStateKey
      if (newRemainingKeys.isEmpty)
        becomeWaitingForStateInitialized()
      else
        context.become(waitingForState(newRemainingKeys))

    case GetFailure(CoordinatorStateKey, _) ⇒
      log.error(
        "The ShardCoordinator was unable to get an initial state within 'waiting-for-state-timeout': {} millis (retrying). Has ClusterSharding been started on all nodes?",
        readMajority.timeout.toMillis)
      // repeat until GetSuccess
      getCoordinatorState()

    case NotFound(CoordinatorStateKey, _) ⇒
      val newRemainingKeys = remainingKeys - CoordinatorStateKey
      if (newRemainingKeys.isEmpty)
        becomeWaitingForStateInitialized()
      else
        context.become(waitingForState(newRemainingKeys))

    case g @ GetSuccess(AllShardsKey, _) ⇒
      shards = g.get(AllShardsKey).elements
      val newUnallocatedShards = state.unallocatedShards union (shards diff state.shards.keySet)
      state = state.copy(unallocatedShards = newUnallocatedShards)
      val newRemainingKeys = remainingKeys - AllShardsKey
      if (newRemainingKeys.isEmpty)
        becomeWaitingForStateInitialized()
      else
        context.become(waitingForState(newRemainingKeys))

    case GetFailure(AllShardsKey, _) ⇒
      log.error(
        "The ShardCoordinator was unable to get all shards state within 'waiting-for-state-timeout': {} millis (retrying)",
        readMajority.timeout.toMillis)
      // repeat until GetSuccess
      getAllShards()

    case NotFound(AllShardsKey, _) ⇒
      val newRemainingKeys = remainingKeys - AllShardsKey
      if (newRemainingKeys.isEmpty)
        becomeWaitingForStateInitialized()
      else
        context.become(waitingForState(newRemainingKeys))

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
    case StateInitialized ⇒
      unstashAll()
      stateInitialized()
      activate()

    case _ ⇒ stash()
  }

  // this state will stash all messages until it receives UpdateSuccess
  def waitingForUpdate[E <: DomainEvent](evt: E, afterUpdateCallback: E ⇒ Unit,
                                         remainingKeys: Set[Key[ReplicatedData]]): Receive = {
    case UpdateSuccess(CoordinatorStateKey, Some(`evt`)) ⇒
      log.debug("The coordinator state was successfully updated with {}", evt)
      val newRemainingKeys = remainingKeys - CoordinatorStateKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(CoordinatorStateKey, Some(`evt`)) ⇒
      log.error(
        "The ShardCoordinator was unable to update a distributed state within 'updating-state-timeout': {} millis (retrying). " +
          "Perhaps the ShardRegion has not started on all active nodes yet? event={}",
        writeMajority.timeout.toMillis, evt)
      // repeat until UpdateSuccess
      sendCoordinatorStateUpdate(evt)

    case UpdateSuccess(AllShardsKey, Some(newShard: String)) ⇒
      log.debug("The coordinator shards state was successfully updated with {}", newShard)
      val newRemainingKeys = remainingKeys - AllShardsKey
      if (newRemainingKeys.isEmpty)
        unbecomeAfterUpdate(evt, afterUpdateCallback)
      else
        context.become(waitingForUpdate(evt, afterUpdateCallback, newRemainingKeys))

    case UpdateTimeout(AllShardsKey, Some(newShard: String)) ⇒
      log.error(
        "The ShardCoordinator was unable to update shards distributed state within 'updating-state-timeout': {} millis (retrying), event={}",
        writeMajority.timeout.toMillis, evt)
      // repeat until UpdateSuccess
      sendAllShardsUpdate(newShard)

    case ModifyFailure(key, error, cause, _) ⇒
      log.error(
        cause,
        "The ShardCoordinator was unable to update a distributed state {} with error {} and event {}.Coordinator will be restarted",
        key, error, evt)
      throw cause

    case GetShardHome(shard) ⇒
      if (!handleGetShardHome(shard))
        stash() // must wait for update that is in progress

    case _ ⇒ stash()
  }

  private def unbecomeAfterUpdate[E <: DomainEvent](evt: E, afterUpdateCallback: E ⇒ Unit): Unit = {
    context.unbecome()
    afterUpdateCallback(evt)
    unstashAll()
  }

  def activate() = {
    context.become(active)
    log.info("Sharding Coordinator was moved to the active state {}", state)
  }

  override def active: Receive =
    if (rememberEntities) {
      ({
        case chg @ Changed(AllShardsKey) ⇒
          shards = chg.get(AllShardsKey).elements
      }: Receive).orElse[Any, Unit](super.active)
    } else
      super.active

  def update[E <: DomainEvent](evt: E)(f: E ⇒ Unit): Unit = {
    sendCoordinatorStateUpdate(evt)
    evt match {
      case s: ShardHomeAllocated if rememberEntities && !shards(s.shard) ⇒
        sendAllShardsUpdate(s.shard)
        context.become(waitingForUpdate(evt, f, allKeys), discardOld = false)
      case _ ⇒
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
    replicator ! Update(CoordinatorStateKey, LWWRegister(initEmptyState), writeMajority, Some(evt)) { reg ⇒
      reg.withValue(s)
    }
  }

  def sendAllShardsUpdate(newShard: String) = {
    replicator ! Update(AllShardsKey, GSet.empty[String], writeMajority, Some(newShard))(_ + newShard)
  }

}
