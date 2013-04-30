/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.existentials
import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NonFatal
import java.util.UUID
import akka.actor.{ Actor, ActorLogging, ActorRef, Address, Cancellable, Props, PoisonPill, ReceiveTimeout, RootActorPath, Scheduler }
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import akka.actor.ActorSelection

/**
 * Base trait for all cluster messages. All ClusterMessage's are serializable.
 */
trait ClusterMessage extends Serializable

/**
 * INTERNAL API
 * Cluster commands sent by the USER via
 * [[akka.cluster.Cluster]] extension
 * or JMX.
 */
private[cluster] object ClusterUserAction {

  /**
   * Command to initiate join another node (represented by `address`).
   * Join will be sent to the other node.
   */
  @SerialVersionUID(1L)
  case class JoinTo(address: Address)

  /**
   * Command to leave the cluster.
   */
  @SerialVersionUID(1L)
  case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  @SerialVersionUID(1L)
  case class Down(address: Address) extends ClusterMessage

}

/**
 * INTERNAL API
 */
private[cluster] object InternalClusterAction {

  /**
   * Command to join the cluster. Sent when a node wants to join another node (the receiver).
   * @param node the node that wants to join the cluster
   */
  @SerialVersionUID(1L)
  case class Join(node: UniqueAddress, roles: Set[String]) extends ClusterMessage

  /**
   * Reply to Join
   * @param from the sender node in the cluster, i.e. the node that received the Join command
   */
  @SerialVersionUID(1L)
  case class Welcome(from: UniqueAddress, gossip: Gossip) extends ClusterMessage

  /**
   * Command to initiate the process to join the specified
   * seed nodes.
   */
  case class JoinSeedNodes(seedNodes: immutable.IndexedSeq[Address])

  /**
   * Start message of the process to join one of the seed nodes.
   * The node sends `InitJoin` to all seed nodes, which replies
   * with `InitJoinAck`. The first reply is used others are discarded.
   * The node sends `Join` command to the seed node that replied first.
   * If a node is uninitialized it will reply to `InitJoin` with
   * `InitJoinNack`.
   */
  case object JoinSeedNode

  /**
   * @see JoinSeedNode
   */
  @SerialVersionUID(1L)
  case object InitJoin extends ClusterMessage

  /**
   * @see JoinSeedNode
   */
  @SerialVersionUID(1L)
  case class InitJoinAck(address: Address) extends ClusterMessage

  /**
   * @see JoinSeedNode
   */
  @SerialVersionUID(1L)
  case class InitJoinNack(address: Address) extends ClusterMessage

  /**
   * Marker interface for periodic tick messages
   */
  sealed trait Tick

  case object GossipTick extends Tick

  case object HeartbeatTick extends Tick

  case object ReapUnreachableTick extends Tick

  case object MetricsTick extends Tick

  case object LeaderActionsTick extends Tick

  case object PublishStatsTick extends Tick

  case class SendGossipTo(address: Address)

  case object GetClusterCoreRef

  case class PublisherCreated(publisher: ActorRef)

  /**
   * Comand to [[akka.cluster.ClusterDaemon]] to create a
   * [[akka.cluster.OnMemberUpListener]].
   */
  case class AddOnMemberUpListener(callback: Runnable)

  sealed trait SubscriptionMessage
  case class Subscribe(subscriber: ActorRef, to: Class[_]) extends SubscriptionMessage
  case class Unsubscribe(subscriber: ActorRef, to: Option[Class[_]]) extends SubscriptionMessage
  /**
   * @param receiver if `receiver` is defined the event will only be sent to that
   *   actor, otherwise it will be sent to all subscribers via the `eventStream`.
   */
  case class PublishCurrentClusterState(receiver: Option[ActorRef]) extends SubscriptionMessage

  sealed trait PublishMessage
  case class PublishChanges(newGossip: Gossip) extends PublishMessage
  case class PublishEvent(event: ClusterDomainEvent) extends PublishMessage
}

/**
 * INTERNAL API.
 *
 * Cluster commands sent by the LEADER.
 */
private[cluster] object ClusterLeaderAction {

  /**
   * Command to mark a node to be removed from the cluster immediately.
   * Can only be sent by the leader.
   * @param node the node to exit, i.e. destination of the message
   */
  @SerialVersionUID(1L)
  case class Exit(node: UniqueAddress) extends ClusterMessage

  /**
   * Command to remove a node from the cluster immediately.
   * @param node the node to shutdown, i.e. destination of the message
   */
  @SerialVersionUID(1L)
  case class Shutdown(node: UniqueAddress) extends ClusterMessage
}

/**
 * INTERNAL API.
 *
 * Supervisor managing the different Cluster daemons.
 */
private[cluster] final class ClusterDaemon(settings: ClusterSettings) extends Actor with ActorLogging {
  import InternalClusterAction._
  // Important - don't use Cluster(context.system) here because that would
  // cause deadlock. The Cluster extension is currently being created and is waiting
  // for response from GetClusterCoreRef in its constructor.
  val coreSupervisor = context.actorOf(Props[ClusterCoreSupervisor].
    withDispatcher(context.props.dispatcher), name = "core")
  context.actorOf(Props[ClusterHeartbeatReceiver].
    withDispatcher(context.props.dispatcher), name = "heartbeatReceiver")

  def receive = {
    case msg @ GetClusterCoreRef ⇒ coreSupervisor forward msg
    case AddOnMemberUpListener(code) ⇒
      context.actorOf(Props(classOf[OnMemberUpListener], code))
    case PublisherCreated(publisher) ⇒
      if (settings.MetricsEnabled) {
        // metrics must be started after core/publisher to be able
        // to inject the publisher ref to the ClusterMetricsCollector
        context.actorOf(Props(classOf[ClusterMetricsCollector], publisher).
          withDispatcher(context.props.dispatcher), name = "metrics")
      }
  }

}

/**
 * INTERNAL API.
 *
 * ClusterCoreDaemon and ClusterDomainEventPublisher can't be restarted because the state
 * would be obsolete. Shutdown the member if any those actors crashed.
 */
private[cluster] final class ClusterCoreSupervisor extends Actor with ActorLogging {
  import InternalClusterAction._

  val publisher = context.actorOf(Props[ClusterDomainEventPublisher].
    withDispatcher(context.props.dispatcher), name = "publisher")
  val coreDaemon = context.watch(context.actorOf(Props(classOf[ClusterCoreDaemon], publisher).
    withDispatcher(context.props.dispatcher), name = "daemon"))

  context.parent ! PublisherCreated(publisher)

  override val supervisorStrategy =
    OneForOneStrategy() {
      case NonFatal(e) ⇒
        log.error(e, "Cluster node [{}] crashed, [{}] - shutting down...", Cluster(context.system).selfAddress, e.getMessage)
        self ! PoisonPill
        Stop
    }

  override def postStop(): Unit = Cluster(context.system).shutdown()

  def receive = {
    case InternalClusterAction.GetClusterCoreRef ⇒ sender ! coreDaemon
  }
}

/**
 * INTERNAL API.
 */
private[cluster] final class ClusterCoreDaemon(publisher: ActorRef) extends Actor with ActorLogging {
  import ClusterLeaderAction._
  import InternalClusterAction._

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, selfUniqueAddress, scheduler, failureDetector }
  import cluster.settings._

  def vclockName(node: UniqueAddress): String = node.address + "-" + node.uid
  val vclockNode = VectorClock.Node(vclockName(selfUniqueAddress))

  // note that self is not initially member,
  // and the Gossip is not versioned for this 'Node' yet
  var latestGossip: Gossip = Gossip.empty

  var stats = ClusterStats()

  var seedNodeProcess: Option[ActorRef] = None

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCore(address: Address): ActorSelection =
    context.actorSelection(RootActorPath(address) / "system" / "cluster" / "core" / "daemon")

  val heartbeatSender = context.actorOf(Props[ClusterHeartbeatSender].
    withDispatcher(UseDispatcher), name = "heartbeatSender")

  import context.dispatcher

  // start periodic gossip to random nodes in cluster
  val gossipTask = scheduler.schedule(PeriodicTasksInitialDelay.max(GossipInterval),
    GossipInterval, self, GossipTick)

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  val failureDetectorReaperTask = scheduler.schedule(PeriodicTasksInitialDelay.max(UnreachableNodesReaperInterval),
    UnreachableNodesReaperInterval, self, ReapUnreachableTick)

  // start periodic leader action management (only applies for the current leader)
  val leaderActionsTask = scheduler.schedule(PeriodicTasksInitialDelay.max(LeaderActionsInterval),
    LeaderActionsInterval, self, LeaderActionsTick)

  // start periodic publish of current stats
  val publishStatsTask: Option[Cancellable] = PublishStatsInterval match {
    case Duration.Zero | Duration.Undefined | Duration.Inf ⇒ None
    case d: FiniteDuration ⇒
      Some(scheduler.schedule(PeriodicTasksInitialDelay.max(d), d, self, PublishStatsTick))
  }

  override def preStart(): Unit = {
    if (AutoJoin) self ! JoinSeedNodes(SeedNodes)
  }

  override def postStop(): Unit = {
    gossipTask.cancel()
    failureDetectorReaperTask.cancel()
    leaderActionsTask.cancel()
    publishStatsTask foreach { _.cancel() }
  }

  def uninitialized: Actor.Receive = {
    case InitJoin                          ⇒ sender ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒ join(address)
    case JoinSeedNodes(seedNodes)          ⇒ joinSeedNodes(seedNodes)
    case msg: SubscriptionMessage          ⇒ publisher forward msg
    case _: Tick                           ⇒ // ignore periodic tasks until initialized
  }

  def tryingToJoin(joinWith: Address, deadline: Option[Deadline]): Actor.Receive = {
    case Welcome(from, gossip) ⇒ welcome(joinWith, from, gossip)
    case InitJoin              ⇒ sender ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒
      context.become(uninitialized)
      join(address)
    case JoinSeedNodes(seedNodes) ⇒
      context.become(uninitialized)
      joinSeedNodes(seedNodes)
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick ⇒
      if (deadline.exists(_.isOverdue)) {
        context.become(uninitialized)
        if (AutoJoin) joinSeedNodes(SeedNodes)
        else join(joinWith)
      }
  }

  def initialized: Actor.Receive = {
    case msg: GossipEnvelope              ⇒ receiveGossip(msg)
    case GossipTick                       ⇒ gossip()
    case ReapUnreachableTick              ⇒ reapUnreachableMembers()
    case LeaderActionsTick                ⇒ leaderActions()
    case PublishStatsTick                 ⇒ publishInternalStats()
    case InitJoin                         ⇒ initJoin()
    case Join(node, roles)                ⇒ joining(node, roles)
    case ClusterUserAction.Down(address)  ⇒ downing(address)
    case ClusterUserAction.Leave(address) ⇒ leaving(address)
    case Exit(node)                       ⇒ exiting(node)
    case Shutdown(node)                   ⇒ shutdown(node)
    case SendGossipTo(address)            ⇒ sendGossipTo(address)
    case msg: SubscriptionMessage         ⇒ publisher forward msg
    case ClusterUserAction.JoinTo(address) ⇒
      log.info("Trying to join [{}] when already part of a cluster, ignoring", address)

  }

  def removed: Actor.Receive = {
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick                  ⇒ // ignore periodic tasks
  }

  def receive = uninitialized

  def initJoin(): Unit = sender ! InitJoinAck(selfAddress)

  def joinSeedNodes(seedNodes: immutable.IndexedSeq[Address]): Unit = {
    require(seedNodeProcess.isEmpty, "Join seed nodes is already in progress")
    seedNodeProcess =
      if (seedNodes.isEmpty || seedNodes == immutable.IndexedSeq(selfAddress)) {
        self ! ClusterUserAction.JoinTo(selfAddress)
        None
      } else if (seedNodes.head == selfAddress) {
        Some(context.actorOf(Props(classOf[FirstSeedNodeProcess], seedNodes).
          withDispatcher(UseDispatcher), name = "firstSeedNodeProcess"))
      } else {
        Some(context.actorOf(Props(classOf[JoinSeedNodeProcess], seedNodes).
          withDispatcher(UseDispatcher), name = "joinSeedNodeProcess"))
      }
  }

  /**
   * Try to join this cluster node with the node specified by `address`.
   * It's only allowed to join from an empty state, i.e. when not already a member.
   * A `Join(selfUniqueAddress)` command is sent to the node to join,
   * which will reply with a `Welcome` message.
   */
  def join(address: Address): Unit = {
    if (address.protocol != selfAddress.protocol)
      log.warning("Trying to join member with wrong protocol, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, address.protocol)
    else if (address.system != selfAddress.system)
      log.warning("Trying to join member with wrong ActorSystem name, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, address.system)
    else {
      require(latestGossip.members.isEmpty, "Join can only be done from empty state")

      // to support manual join when joining to seed nodes is stuck (no seed nodes available)
      val snd = sender
      seedNodeProcess match {
        case Some(`snd`) ⇒
          // seedNodeProcess completed, it will stop itself
          seedNodeProcess = None
        case Some(s) ⇒
          // manual join, abort current seedNodeProcess
          context stop s
          seedNodeProcess = None
        case None ⇒ // no seedNodeProcess in progress
      }

      if (address == selfAddress) {
        context.become(initialized)
        joining(selfUniqueAddress, cluster.selfRoles)
      } else {
        val joinDeadline = RetryUnsuccessfulJoinAfter match {
          case Duration.Undefined | Duration.Inf ⇒ None
          case d: FiniteDuration                 ⇒ Some(Deadline.now + d)
        }
        context.become(tryingToJoin(address, joinDeadline))
        clusterCore(address) ! Join(selfUniqueAddress, cluster.selfRoles)
      }
    }
  }

  /**
   * State transition to JOINING - new node joining.
   * Received `Join` message and replies with `Welcome` message, containing
   * current gossip state, including the new joining member.
   */
  def joining(node: UniqueAddress, roles: Set[String]): Unit = {
    if (node.address.protocol != selfAddress.protocol)
      log.warning("Member with wrong protocol tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, node.address.protocol)
    else if (node.address.system != selfAddress.system)
      log.warning("Member with wrong ActorSystem name tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, node.address.system)
    else {
      val localMembers = latestGossip.members
      val localUnreachable = latestGossip.overview.unreachable

      // check by address without uid to make sure that node with same host:port is not allowed
      // to join until previous node with that host:port has been removed from the cluster
      val alreadyMember = localMembers.exists(_.address == node.address)
      val isUnreachable = localUnreachable.exists(_.address == node.address)

      if (alreadyMember)
        log.info("Existing member [{}] is trying to join, ignoring", node)
      else if (isUnreachable)
        log.info("Unreachable member [{}] is trying to join, ignoring", node)
      else {

        // remove the node from the failure detector
        failureDetector.remove(node.address)

        // add joining node as Joining
        // add self in case someone else joins before self has joined (Set discards duplicates)
        val newMembers = localMembers + Member(node, roles) + Member(selfUniqueAddress, cluster.selfRoles)
        val newGossip = latestGossip copy (members = newMembers)

        val versionedGossip = newGossip :+ vclockNode
        val seenVersionedGossip = versionedGossip seen selfUniqueAddress

        latestGossip = seenVersionedGossip

        log.info("Cluster Node [{}] - Node [{}] is JOINING, roles [{}]", selfAddress, node.address, roles.mkString(", "))
        if (node != selfUniqueAddress) {
          clusterCore(node.address) ! Welcome(selfUniqueAddress, latestGossip)
        }

        publish(latestGossip)
      }
    }
  }

  /**
   * Reply from Join request.
   */
  def welcome(joinWith: Address, from: UniqueAddress, gossip: Gossip): Unit = {
    require(latestGossip.members.isEmpty, "Join can only be done from empty state")
    if (joinWith != from.address)
      log.info("Ignoring welcome from [{}] when trying to join with [{}]", from.address, joinWith)
    else {
      log.info("Cluster Node [{}] - Welcome from [{}]", selfAddress, from.address)
      latestGossip = gossip seen selfUniqueAddress
      publish(latestGossip)
      if (from != selfUniqueAddress)
        oneWayGossipTo(from)
      context.become(initialized)
    }
  }

  /**
   * State transition to LEAVING.
   * The node will eventually be removed by the leader, after hand-off in EXITING, and only after
   * removal a new node with same address can join the cluster through the normal joining procedure.
   */
  def leaving(address: Address): Unit = {
    // only try to update if the node is available (in the member ring)
    if (latestGossip.members.exists(m ⇒ m.address == address && m.status == Up)) {
      val newMembers = latestGossip.members map { m ⇒ if (m.address == address) m.copy(status = Leaving) else m } // mark node as LEAVING
      val newGossip = latestGossip copy (members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfUniqueAddress

      latestGossip = seenVersionedGossip

      log.info("Cluster Node [{}] - Marked address [{}] as [{}]", selfAddress, address, Leaving)
      publish(latestGossip)
    }
  }

  /**
   * State transition to EXITING.
   */
  def exiting(node: UniqueAddress): Unit =
    if (node == selfUniqueAddress) {
      log.info("Cluster Node [{}] - Marked as [{}]", selfAddress, Exiting)
      // TODO implement when we need hand-off
    }

  /**
   * This method is only called after the LEADER has sent a Shutdown message - telling the node
   * to shut down himself.
   */
  def shutdown(node: UniqueAddress): Unit =
    if (node == selfUniqueAddress) {
      log.info("Cluster Node [{}] - Node has been REMOVED by the leader - shutting down...", selfAddress)
      cluster.shutdown()
    }

  /**
   * State transition to DOW.
   * The node to DOWN is removed from the `members` set and put in the `unreachable` set (if not already there)
   * and its status is set to DOWN. The node is also removed from the `seen` table.
   *
   * The node will eventually be removed by the leader, and only after removal a new node with same address can
   * join the cluster through the normal joining procedure.
   */
  def downing(address: Address): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen
    val localUnreachableMembers = localOverview.unreachable

    // 1. check if the node to DOWN is in the `members` set
    val downedMember: Option[Member] =
      localMembers.collectFirst { case m if m.address == address ⇒ m.copy(status = Down) }

    val newMembers = downedMember match {
      case Some(m) ⇒
        log.info("Cluster Node [{}] - Marking node [{}] as [{}]", selfAddress, m.address, Down)
        localMembers - m
      case None ⇒ localMembers
    }

    // 2. check if the node to DOWN is in the `unreachable` set
    val newUnreachableMembers =
      localUnreachableMembers.map { member ⇒
        // no need to DOWN members already DOWN
        if (member.address == address && member.status != Down) {
          log.info("Cluster Node [{}] - Marking unreachable node [{}] as [{}]", selfAddress, member.address, Down)
          member copy (status = Down)
        } else member
      }

    // 3. add the newly DOWNED members from the `members` (in step 1.) to the `newUnreachableMembers` set.
    val newUnreachablePlusNewlyDownedMembers = newUnreachableMembers ++ downedMember

    // 4. remove nodes marked as DOWN from the `seen` table
    val newSeen = localSeen -- newUnreachablePlusNewlyDownedMembers.collect { case m if m.status == Down ⇒ m.uniqueAddress }

    // update gossip overview
    val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachablePlusNewlyDownedMembers)
    val newGossip = localGossip copy (overview = newOverview, members = newMembers) // update gossip
    val versionedGossip = newGossip :+ vclockNode
    latestGossip = versionedGossip seen selfUniqueAddress

    publish(latestGossip)
  }

  /**
   * Receive new gossip.
   */
  def receiveGossip(envelope: GossipEnvelope): Unit = {
    val from = envelope.from
    val remoteGossip = envelope.gossip
    val localGossip = latestGossip

    if (envelope.to != selfUniqueAddress)
      log.info("Ignoring received gossip intended for someone else, from [{}] to [{}]", from.address, envelope.to)
    if (remoteGossip.overview.unreachable.exists(_.address == selfAddress))
      log.info("Ignoring received gossip with myself as unreachable, from [{}]", selfAddress, from.address)
    else if (localGossip.overview.unreachable.exists(_.uniqueAddress == from))
      log.info("Ignoring received gossip from unreachable [{}] ", from)
    else if (localGossip.members.forall(_.uniqueAddress != from))
      log.info("Ignoring received gossip from unknown [{}]", from)
    else if (remoteGossip.members.forall(_.uniqueAddress != selfUniqueAddress))
      log.info("Ignoring received gossip that does not contain myself, from [{}]", from)
    else {

      val comparison = remoteGossip.version tryCompareTo localGossip.version
      val conflict = comparison.isEmpty

      val (winningGossip, talkback, newStats) = comparison match {
        case None ⇒
          // conflicting versions, merge
          (remoteGossip merge localGossip, true, stats.incrementMergeCount)
        case Some(0) ⇒
          // same version
          (remoteGossip mergeSeen localGossip, !remoteGossip.seenByNode(selfUniqueAddress), stats.incrementSameCount)
        case Some(x) if x < 0 ⇒
          // local is newer
          (localGossip, true, stats.incrementNewerCount)
        case _ ⇒
          // remote is newer
          (remoteGossip, !remoteGossip.seenByNode(selfUniqueAddress), stats.incrementOlderCount)
      }

      stats = newStats
      latestGossip = winningGossip seen selfUniqueAddress

      // for all new joining nodes we remove them from the failure detector
      latestGossip.members foreach {
        node ⇒ if (node.status == Joining && !localGossip.members(node)) failureDetector.remove(node.address)
      }

      log.debug("Cluster Node [{}] - Receiving gossip from [{}]", selfAddress, from)

      if (conflict) {
        log.debug(
          """Couldn't establish a causal relationship between "remote" gossip and "local" gossip - Remote[{}] - Local[{}] - merged them into [{}]""",
          remoteGossip, localGossip, winningGossip)
      }

      stats = stats.incrementReceivedGossipCount
      publish(latestGossip)

      if (envelope.conversation && talkback) {
        // send back gossip to sender when sender had different view, i.e. merge, or sender had
        // older or sender had newer
        gossipTo(from)
      }
    }
  }

  def mergeRate(count: Long): Double = (count * 1000.0) / GossipInterval.toMillis

  /**
   * Initiates a new round of gossip.
   */
  def gossip(): Unit = {
    log.debug("Cluster Node [{}] - Initiating new round of gossip", selfAddress)

    if (!isSingletonCluster && isAvailable) {
      val localGossip = latestGossip

      val preferredGossipTargets =
        if (ThreadLocalRandom.current.nextDouble() < GossipDifferentViewProbability) { // If it's time to try to gossip to some nodes with a different view
          // gossip to a random alive member with preference to a member with older or newer gossip version
          val localMemberAddressesSet = localGossip.members map { _.uniqueAddress }
          val nodesWithDifferentView = for {
            (node, version) ← localGossip.overview.seen
            if localMemberAddressesSet contains node
            if version != localGossip.version
          } yield node

          nodesWithDifferentView.toIndexedSeq
        } else Vector.empty[UniqueAddress]

      gossipToRandomNodeOf(
        if (preferredGossipTargets.nonEmpty) preferredGossipTargets
        else localGossip.members.toIndexedSeq.map(_.uniqueAddress) // Fall back to localGossip; important to not accidentally use `map` of the SortedSet, since the original order is not preserved)
        )
    }
  }

  /**
   * Runs periodic leader actions, such as member status transitions, auto-downing unreachable nodes,
   * assigning partitions etc.
   */
  def leaderActions(): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members

    val isLeader = localGossip.isLeader(selfUniqueAddress)

    if (isLeader && isAvailable) {
      // only run the leader actions if we are the LEADER and available

      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localUnreachableMembers = localOverview.unreachable
      val hasPartionHandoffCompletedSuccessfully: Boolean = {
        // FIXME implement partion handoff and a check if it is completed - now just returns TRUE - e.g. has completed successfully
        true
      }

      // Leader actions are as follows:
      //   1. Move JOINING     => UP          -- When a node joins the cluster
      //   2. Move LEAVING     => EXITING     -- When all partition handoff has completed
      //   3. Non-exiting remain              -- When all partition handoff has completed
      //   4. Move EXITING     => REMOVED     -- When all nodes have seen that the node is EXITING (convergence) - remove the nodes from the node ring and seen table
      //   5. Move UNREACHABLE => DOWN        -- When the node is in the UNREACHABLE set it can be auto-down by leader
      //   6. Move DOWN        => REMOVED     -- When all nodes have seen that the node is DOWN (convergence) - remove the nodes from the node ring and seen table
      //   7. Updating the vclock version for the changes
      //   8. Updating the `seen` table
      //   9. Try to update the state with the new gossip
      //  10. If success - run all the side-effecting processing

      val (
        newGossip: Gossip,
        hasChangedState: Boolean,
        upMembers,
        exitingMembers,
        removedMembers,
        removedUnreachableMembers,
        unreachableButNotDownedMembers) =

        if (localGossip.convergence) {
          // we have convergence - so we can't have unreachable nodes

          def enoughMembers: Boolean = {
            localMembers.size >= MinNrOfMembers && MinNrOfMembersOfRole.forall {
              case (role, threshold) ⇒ localMembers.count(_.hasRole(role)) >= threshold
            }
          }
          def isJoiningToUp(m: Member): Boolean = m.status == Joining && enoughMembers

          // transform the node member ring
          val newMembers = localMembers collect {
            // Move JOINING => UP (once all nodes have seen that this node is JOINING, i.e. we have a convergence)
            // and minimum number of nodes have joined the cluster
            case member if isJoiningToUp(member) ⇒ member copy (status = Up)
            // Move LEAVING => EXITING (once we have a convergence on LEAVING
            // *and* if we have a successful partition handoff)
            case member if member.status == Leaving && hasPartionHandoffCompletedSuccessfully ⇒
              member copy (status = Exiting)
            // Everyone else that is not Exiting stays as they are
            case member if member.status != Exiting && member.status != Down ⇒ member
            // Move EXITING => REMOVED, DOWN => REMOVED - i.e. remove the nodes from the `members` set/node ring and seen table
          }

          // ----------------------
          // Store away all stuff needed for the side-effecting processing
          // ----------------------

          // Check for the need to do side-effecting on successful state change
          // Repeat the checking for transitions between JOINING -> UP, LEAVING -> EXITING, EXITING -> REMOVED, DOWN -> REMOVED
          // to check for state-changes and to store away removed and exiting members for later notification
          //    1. check for state-changes to update
          //    2. store away removed and exiting members so we can separate the pure state changes
          val (removedMembers, newMembers1) = localMembers partition (m ⇒ m.status == Exiting || m.status == Down)
          val (removedUnreachable, newUnreachable) = localUnreachableMembers partition (_.status == Down)

          val (upMembers, newMembers2) = newMembers1 partition (isJoiningToUp(_))

          val exitingMembers = newMembers2 filter (_.status == Leaving && hasPartionHandoffCompletedSuccessfully)

          val hasChangedState = removedMembers.nonEmpty || removedUnreachable.nonEmpty || upMembers.nonEmpty || exitingMembers.nonEmpty

          // removing REMOVED nodes from the `seen` table
          val newSeen = localSeen -- removedMembers.map(_.uniqueAddress) -- removedUnreachable.map(_.uniqueAddress)

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachable) // update gossip overview
          val newGossip = localGossip copy (members = newMembers, overview = newOverview) // update gossip

          (newGossip, hasChangedState, upMembers, exitingMembers, removedMembers, removedUnreachable, Member.none)

        } else if (AutoDown) {
          // we don't have convergence - so we might have unreachable nodes

          // if 'auto-down' is turned on, then try to auto-down any unreachable nodes
          val newUnreachableMembers = localUnreachableMembers collect {
            // ----------------------
            // Move UNREACHABLE => DOWN (auto-downing by leader)
            // ----------------------
            case member if member.status != Down ⇒ member copy (status = Down)
            case downMember                      ⇒ downMember // no need to DOWN members already DOWN
          }

          // Check for the need to do side-effecting on successful state change
          val unreachableButNotDownedMembers = localUnreachableMembers filter (_.status != Down)

          // removing nodes marked as DOWN from the `seen` table
          val newSeen = localSeen -- newUnreachableMembers.collect { case m if m.status == Down ⇒ m.uniqueAddress }

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (overview = newOverview) // update gossip

          (newGossip, unreachableButNotDownedMembers.nonEmpty, Member.none, Member.none, Member.none, Member.none, unreachableButNotDownedMembers)

        } else (localGossip, false, Member.none, Member.none, Member.none, Member.none, Member.none)

      if (hasChangedState) { // we have a change of state - version it and try to update
        // ----------------------
        // Updating the vclock version for the changes
        // ----------------------
        val versionedGossip = newGossip :+ vclockNode

        // ----------------------
        // Updating the `seen` table
        // Unless the leader (this node) is part of the removed members, i.e. the leader have moved himself from EXITING -> REMOVED
        // ----------------------
        val seenVersionedGossip =
          if (removedMembers.exists(_.uniqueAddress == selfUniqueAddress)) versionedGossip
          else versionedGossip seen selfUniqueAddress

        // ----------------------
        // Update the state with the new gossip
        // ----------------------
        latestGossip = seenVersionedGossip

        // ----------------------
        // Run all the side-effecting processing
        // ----------------------

        // log the move of members from joining to up
        upMembers foreach { member ⇒
          log.info("Cluster Node [{}] - Leader is moving node [{}] from [{}] to [{}]",
            selfAddress, member.address, member.status, Up)
        }

        //  tell all removed members to remove and shut down themselves
        removedMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from [{}] to [{}] - and removing node from node ring",
            selfAddress, address, member.status, Removed)
          clusterCore(address) ! ClusterLeaderAction.Shutdown(member.uniqueAddress)
        }

        //  tell all exiting members to exit
        exitingMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from [{}] to [{}]",
            selfAddress, address, member.status, Exiting)
          clusterCore(address) ! ClusterLeaderAction.Exit(member.uniqueAddress) // FIXME should wait for completion of handoff?
        }

        // log the auto-downing of the unreachable nodes
        unreachableButNotDownedMembers foreach { member ⇒
          log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as [{}]", selfAddress, member.address, Down)
        }

        // log the auto-downing of the unreachable nodes
        removedUnreachableMembers foreach { member ⇒
          log.info("Cluster Node [{}] - Leader is removing unreachable node [{}]", selfAddress, member.address)
        }

        publish(latestGossip)
      }
    }
  }

  /**
   * Reaps the unreachable members (moves them to the `unreachable` list in the cluster overview) according to the failure detector's verdict.
   */
  def reapUnreachableMembers(): Unit = {
    if (!isSingletonCluster && isAvailable) {
      // only scrutinize if we are a non-singleton cluster and available

      val localGossip = latestGossip
      val localOverview = localGossip.overview
      val localMembers = localGossip.members
      val localUnreachableMembers = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒
        member.uniqueAddress == selfUniqueAddress || failureDetector.isAvailable(member.address)
      }

      if (newlyDetectedUnreachableMembers.nonEmpty) {

        val newMembers = localMembers -- newlyDetectedUnreachableMembers
        val newUnreachableMembers = localUnreachableMembers ++ newlyDetectedUnreachableMembers

        val newOverview = localOverview copy (unreachable = newUnreachableMembers)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        // updating vclock and `seen` table
        val versionedGossip = newGossip :+ vclockNode
        val seenVersionedGossip = versionedGossip seen selfUniqueAddress

        latestGossip = seenVersionedGossip

        log.error("Cluster Node [{}] - Marking node(s) as UNREACHABLE [{}]", selfAddress, newlyDetectedUnreachableMembers.mkString(", "))

        publish(latestGossip)
      }
    }
  }

  def selectRandomNode(nodes: IndexedSeq[UniqueAddress]): Option[UniqueAddress] =
    if (nodes.isEmpty) None
    else Some(nodes(ThreadLocalRandom.current nextInt nodes.size))

  def isSingletonCluster: Boolean = latestGossip.isSingletonCluster

  def isAvailable: Boolean = !latestGossip.isUnreachable(selfUniqueAddress)

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return the used [[UniqueAddress]] if any
   */
  private def gossipToRandomNodeOf(nodes: immutable.IndexedSeq[UniqueAddress]): Option[UniqueAddress] = {
    // filter out myself
    val peer = selectRandomNode(nodes filterNot (_ == selfUniqueAddress))
    peer foreach gossipTo
    peer
  }

  // needed for tests
  def sendGossipTo(address: Address): Unit = {
    latestGossip.members.foreach(m ⇒
      if (m.address == address)
        gossipTo(m.uniqueAddress))
  }

  /**
   * Gossips latest gossip to a node.
   */
  def gossipTo(node: UniqueAddress): Unit =
    gossipTo(node, GossipEnvelope(selfUniqueAddress, node, latestGossip, conversation = true))

  def oneWayGossipTo(node: UniqueAddress): Unit =
    gossipTo(node, GossipEnvelope(selfUniqueAddress, node, latestGossip, conversation = false))

  def gossipTo(node: UniqueAddress, gossipMsg: GossipEnvelope): Unit =
    if (node != selfUniqueAddress && gossipMsg.gossip.members.exists(_.uniqueAddress == node))
      clusterCore(node.address) ! gossipMsg

  def publish(newGossip: Gossip): Unit = {
    publisher ! PublishChanges(newGossip)
    if (PublishStatsInterval == Duration.Zero) publishInternalStats()
  }

  def publishInternalStats(): Unit = publisher ! CurrentInternalStats(stats)

}

/**
 * INTERNAL API.
 *
 * Used only for the first seed node.
 * Sends InitJoin to all seed nodes (except itself).
 * If other seed nodes are not part of the cluster yet they will reply with
 * InitJoinNack or not respond at all and then the first seed node
 * will join itself to initialize the new cluster. When the first
 * seed node is restarted, and some other seed node is part of the cluster
 * it will reply with InitJoinAck and then the first seed node will join
 * that other seed node to join existing cluster.
 */
private[cluster] final class FirstSeedNodeProcess(seedNodes: immutable.IndexedSeq[Address]) extends Actor with ActorLogging {
  import InternalClusterAction._
  import ClusterUserAction.JoinTo

  val cluster = Cluster(context.system)
  def selfAddress = cluster.selfAddress

  if (seedNodes.size <= 1 || seedNodes.head != selfAddress)
    throw new IllegalArgumentException("Join seed node should not be done")

  val timeout = Deadline.now + cluster.settings.SeedNodeTimeout

  var remainingSeedNodes = seedNodes.toSet - selfAddress

  // retry until one ack, or all nack, or timeout
  import context.dispatcher
  val retryTask = cluster.scheduler.schedule(1.second, 1.second, self, JoinSeedNode)
  self ! JoinSeedNode

  override def postStop(): Unit = retryTask.cancel()

  def receive = {
    case JoinSeedNode ⇒
      if (timeout.hasTimeLeft) {
        // send InitJoin to remaining seed nodes (except myself)
        remainingSeedNodes foreach { a ⇒ context.actorSelection(context.parent.path.toStringWithAddress(a)) ! InitJoin }
      } else {
        // no InitJoinAck received, initialize new cluster by joining myself
        context.parent ! JoinTo(selfAddress)
        context.stop(self)
      }
    case InitJoinAck(address) ⇒
      // first InitJoinAck reply, join existing cluster
      context.parent ! JoinTo(address)
      context.stop(self)
    case InitJoinNack(address) ⇒
      remainingSeedNodes -= address
      if (remainingSeedNodes.isEmpty) {
        // initialize new cluster by joining myself when nacks from all other seed nodes
        context.parent ! JoinTo(selfAddress)
        context.stop(self)
      }
  }

}

/**
 * INTERNAL API.
 *
 * Sends InitJoin to all seed nodes (except itself) and expect
 * InitJoinAck reply back. The seed node that replied first
 * will be used, joined to. InitJoinAck replies received after the
 * first one are ignored.
 *
 * Retries if no InitJoinAck replies are received within the
 * SeedNodeTimeout.
 * When at least one reply has been received it stops itself after
 * an idle SeedNodeTimeout.
 *
 * The seed nodes can be started in any order, but they will not be "active",
 * until they have been able to join another seed node (seed1).
 * They will retry the join procedure.
 * So one possible startup scenario is:
 * 1. seed2 started, but doesn't get any ack from seed1 or seed3
 * 2. seed3 started, doesn't get any ack from seed1 or seed3 (seed2 doesn't reply)
 * 3. seed1 is started and joins itself
 * 4. seed2 retries the join procedure and gets an ack from seed1, and then joins to seed1
 * 5. seed3 retries the join procedure and gets acks from seed2 first, and then joins to seed2
 *
 */
private[cluster] final class JoinSeedNodeProcess(seedNodes: immutable.IndexedSeq[Address]) extends Actor with ActorLogging {
  import InternalClusterAction._
  import ClusterUserAction.JoinTo

  def selfAddress = Cluster(context.system).selfAddress

  if (seedNodes.isEmpty || seedNodes.head == selfAddress)
    throw new IllegalArgumentException("Join seed node should not be done")

  context.setReceiveTimeout(Cluster(context.system).settings.SeedNodeTimeout)

  override def preStart(): Unit = self ! JoinSeedNode

  def receive = {
    case JoinSeedNode ⇒
      // send InitJoin to all seed nodes (except myself)
      seedNodes.collect {
        case a if a != selfAddress ⇒ context.actorSelection(context.parent.path.toStringWithAddress(a))
      } foreach { _ ! InitJoin }
    case InitJoinAck(address) ⇒
      // first InitJoinAck reply
      context.parent ! JoinTo(address)
      context.become(done)
    case InitJoinNack(_) ⇒ // that seed was uninitialized
    case ReceiveTimeout ⇒
      // no InitJoinAck received, try again
      self ! JoinSeedNode
  }

  def done: Actor.Receive = {
    case InitJoinAck(_) ⇒ // already received one, skip rest
    case ReceiveTimeout ⇒ context.stop(self)
  }
}

/**
 * INTERNAL API
 *
 * The supplied callback will be run, once, when current cluster member is `Up`.
 */
private[cluster] class OnMemberUpListener(callback: Runnable) extends Actor with ActorLogging {
  import ClusterEvent._
  val cluster = Cluster(context.system)
  // subscribe to MemberUp, re-subscribe when restart
  override def preStart(): Unit =
    cluster.subscribe(self, classOf[MemberUp])
  override def postStop(): Unit =
    cluster.unsubscribe(self)

  def receive = {
    case state: CurrentClusterState ⇒
      if (state.members.exists(isSelfUp(_)))
        done()
    case MemberUp(m) ⇒
      if (isSelfUp(m))
        done()
  }

  def done(): Unit = {
    try callback.run() catch {
      case NonFatal(e) ⇒ log.error(e, "OnMemberUp callback failed with [{}]", e.getMessage)
    } finally {
      context stop self
    }
  }

  def isSelfUp(m: Member): Boolean =
    m.uniqueAddress == cluster.selfUniqueAddress && m.status == MemberStatus.Up

}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[cluster] case class ClusterStats(
  receivedGossipCount: Long = 0L,
  mergeCount: Long = 0L,
  sameCount: Long = 0L,
  newerCount: Long = 0L,
  olderCount: Long = 0L) {

  def incrementReceivedGossipCount(): ClusterStats =
    copy(receivedGossipCount = receivedGossipCount + 1)

  def incrementMergeCount(): ClusterStats =
    copy(mergeCount = mergeCount + 1)

  def incrementSameCount(): ClusterStats =
    copy(sameCount = sameCount + 1)

  def incrementNewerCount(): ClusterStats =
    copy(newerCount = newerCount + 1)

  def incrementOlderCount(): ClusterStats =
    copy(olderCount = olderCount + 1)

  def :+(that: ClusterStats): ClusterStats = {
    ClusterStats(
      this.receivedGossipCount + that.receivedGossipCount,
      this.mergeCount + that.mergeCount,
      this.sameCount + that.sameCount,
      this.newerCount + that.newerCount,
      this.olderCount + that.olderCount)
  }

  def :-(that: ClusterStats): ClusterStats = {
    ClusterStats(
      this.receivedGossipCount - that.receivedGossipCount,
      this.mergeCount - that.mergeCount,
      this.sameCount - that.sameCount,
      this.newerCount - that.newerCount,
      this.olderCount - that.olderCount)
  }

}
