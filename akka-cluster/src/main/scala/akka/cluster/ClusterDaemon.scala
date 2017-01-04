/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import language.existentials
import scala.collection.immutable
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom
import scala.util.control.NonFatal
import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }
import scala.collection.breakOut
import akka.remote.QuarantinedEvent
import java.util.ArrayList
import java.util.Collections

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
  final case class JoinTo(address: Address)

  /**
   * Command to leave the cluster.
   */
  @SerialVersionUID(1L)
  final case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  @SerialVersionUID(1L)
  final case class Down(address: Address) extends ClusterMessage

}

/**
 * INTERNAL API
 */
private[cluster] object InternalClusterAction {

  /**
   * Command to join the cluster. Sent when a node wants to join another node (the receiver).
   *
   * @param node the node that wants to join the cluster
   */
  @SerialVersionUID(1L)
  final case class Join(node: UniqueAddress, roles: Set[String]) extends ClusterMessage

  /**
   * Reply to Join
   *
   * @param from the sender node in the cluster, i.e. the node that received the Join command
   */
  @SerialVersionUID(1L)
  final case class Welcome(from: UniqueAddress, gossip: Gossip) extends ClusterMessage

  /**
   * Command to initiate the process to join the specified
   * seed nodes.
   */
  final case class JoinSeedNodes(seedNodes: immutable.IndexedSeq[Address])

  /**
   * Start message of the process to join one of the seed nodes.
   * The node sends `InitJoin` to all seed nodes, which replies
   * with `InitJoinAck`. The first reply is used others are discarded.
   * The node sends `Join` command to the seed node that replied first.
   * If a node is uninitialized it will reply to `InitJoin` with
   * `InitJoinNack`.
   */
  case object JoinSeedNode extends DeadLetterSuppression

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  case object InitJoin extends ClusterMessage with DeadLetterSuppression

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  final case class InitJoinAck(address: Address) extends ClusterMessage with DeadLetterSuppression

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  final case class InitJoinNack(address: Address) extends ClusterMessage with DeadLetterSuppression

  /**
   * Marker interface for periodic tick messages
   */
  sealed trait Tick

  case object GossipTick extends Tick

  case object GossipSpeedupTick extends Tick

  case object ReapUnreachableTick extends Tick

  case object MetricsTick extends Tick

  case object LeaderActionsTick extends Tick

  case object PublishStatsTick extends Tick

  final case class SendGossipTo(address: Address)

  case object GetClusterCoreRef

  final case class PublisherCreated(publisher: ActorRef)

  /**
   * Command to [[akka.cluster.ClusterDaemon]] to create a
   * [[akka.cluster.OnMemberStatusChangedListener]].
   */
  final case class AddOnMemberUpListener(callback: Runnable) extends NoSerializationVerificationNeeded

  final case class AddOnMemberRemovedListener(callback: Runnable) extends NoSerializationVerificationNeeded

  sealed trait SubscriptionMessage
  final case class Subscribe(subscriber: ActorRef, initialStateMode: SubscriptionInitialStateMode, to: Set[Class[_]]) extends SubscriptionMessage
  final case class Unsubscribe(subscriber: ActorRef, to: Option[Class[_]]) extends SubscriptionMessage
  /**
   * @param receiver [[akka.cluster.ClusterEvent.CurrentClusterState]] will be sent to the `receiver`
   */
  final case class SendCurrentClusterState(receiver: ActorRef) extends SubscriptionMessage

  sealed trait PublishMessage
  final case class PublishChanges(newGossip: Gossip) extends PublishMessage
  final case class PublishEvent(event: ClusterDomainEvent) extends PublishMessage
}

/**
 * INTERNAL API.
 *
 * Supervisor managing the different Cluster daemons.
 */
private[cluster] final class ClusterDaemon(settings: ClusterSettings) extends Actor with ActorLogging
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._
  // Important - don't use Cluster(context.system) in constructor because that would
  // cause deadlock. The Cluster extension is currently being created and is waiting
  // for response from GetClusterCoreRef in its constructor.
  // Child actors are therefore created when GetClusterCoreRef is received
  var coreSupervisor: Option[ActorRef] = None

  def createChildren(): Unit = {
    coreSupervisor = Some(context.actorOf(Props[ClusterCoreSupervisor].
      withDispatcher(context.props.dispatcher), name = "core"))
    context.actorOf(Props[ClusterHeartbeatReceiver].
      withDispatcher(context.props.dispatcher), name = "heartbeatReceiver")
  }

  def receive = {
    case msg: GetClusterCoreRef.type ⇒
      if (coreSupervisor.isEmpty)
        createChildren()
      coreSupervisor.foreach(_ forward msg)
    case AddOnMemberUpListener(code) ⇒
      context.actorOf(Props(classOf[OnMemberStatusChangedListener], code, Up).withDeploy(Deploy.local))
    case AddOnMemberRemovedListener(code) ⇒
      context.actorOf(Props(classOf[OnMemberStatusChangedListener], code, Removed).withDeploy(Deploy.local))
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
private[cluster] final class ClusterCoreSupervisor extends Actor with ActorLogging
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._

  // Important - don't use Cluster(context.system) in constructor because that would
  // cause deadlock. The Cluster extension is currently being created and is waiting
  // for response from GetClusterCoreRef in its constructor.
  // Child actors are therefore created when GetClusterCoreRef is received

  var coreDaemon: Option[ActorRef] = None

  def createChildren(): Unit = {
    val publisher = context.actorOf(Props[ClusterDomainEventPublisher].
      withDispatcher(context.props.dispatcher), name = "publisher")
    coreDaemon = Some(context.watch(context.actorOf(Props(classOf[ClusterCoreDaemon], publisher).
      withDispatcher(context.props.dispatcher), name = "daemon")))
    context.parent ! PublisherCreated(publisher)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case NonFatal(e) ⇒
        log.error(e, "Cluster node [{}] crashed, [{}] - shutting down...", Cluster(context.system).selfAddress, e.getMessage)
        self ! PoisonPill
        Stop
    }

  override def postStop(): Unit = Cluster(context.system).shutdown()

  def receive = {
    case InternalClusterAction.GetClusterCoreRef ⇒
      if (coreDaemon.isEmpty)
        createChildren()
      coreDaemon.foreach(sender() ! _)
  }
}

/**
 * INTERNAL API.
 */
private[cluster] class ClusterCoreDaemon(publisher: ActorRef) extends Actor with ActorLogging
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, selfRoles, scheduler, failureDetector }
  import cluster.settings._
  import cluster.InfoLogger._

  protected def selfUniqueAddress = cluster.selfUniqueAddress

  val NumberOfGossipsBeforeShutdownWhenLeaderExits = 5
  val MaxGossipsBeforeShuttingDownMyself = 5

  def vclockName(node: UniqueAddress): String = s"${node.address}-${node.longUid}"
  val vclockNode = VectorClock.Node(vclockName(selfUniqueAddress))

  // note that self is not initially member,
  // and the Gossip is not versioned for this 'Node' yet
  var latestGossip: Gossip = Gossip.empty

  val statsEnabled = PublishStatsInterval.isFinite
  var gossipStats = GossipStats()

  var seedNodes = SeedNodes
  var seedNodeProcess: Option[ActorRef] = None
  var seedNodeProcessCounter = 0 // for unique names
  var leaderActionCounter = 0

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCore(address: Address): ActorSelection =
    context.actorSelection(RootActorPath(address) / "system" / "cluster" / "core" / "daemon")

  import context.dispatcher

  // start periodic gossip to random nodes in cluster
  val gossipTask = scheduler.schedule(
    PeriodicTasksInitialDelay.max(GossipInterval),
    GossipInterval, self, GossipTick)

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  val failureDetectorReaperTask = scheduler.schedule(
    PeriodicTasksInitialDelay.max(UnreachableNodesReaperInterval),
    UnreachableNodesReaperInterval, self, ReapUnreachableTick)

  // start periodic leader action management (only applies for the current leader)
  val leaderActionsTask = scheduler.schedule(
    PeriodicTasksInitialDelay.max(LeaderActionsInterval),
    LeaderActionsInterval, self, LeaderActionsTick)

  // start periodic publish of current stats
  val publishStatsTask: Option[Cancellable] = PublishStatsInterval match {
    case Duration.Zero | _: Duration.Infinite ⇒ None
    case d: FiniteDuration ⇒
      Some(scheduler.schedule(PeriodicTasksInitialDelay.max(d), d, self, PublishStatsTick))
  }

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[QuarantinedEvent])

    cluster.downingProvider.downingActorProps.foreach { props ⇒
      val propsWithDispatcher =
        if (props.dispatcher == Deploy.NoDispatcherGiven) props.withDispatcher(context.props.dispatcher)
        else props

      context.actorOf(propsWithDispatcher, name = "downingProvider")
    }

    if (seedNodes.isEmpty)
      logInfo("No seed-nodes configured, manual cluster join required")
    else
      self ! JoinSeedNodes(seedNodes)
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    gossipTask.cancel()
    failureDetectorReaperTask.cancel()
    leaderActionsTask.cancel()
    publishStatsTask foreach { _.cancel() }
  }

  def uninitialized: Actor.Receive = {
    case InitJoin                          ⇒ sender() ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒ join(address)
    case JoinSeedNodes(newSeedNodes)       ⇒ joinSeedNodes(newSeedNodes)
    case msg: SubscriptionMessage          ⇒ publisher forward msg
  }

  def tryingToJoin(joinWith: Address, deadline: Option[Deadline]): Actor.Receive = {
    case Welcome(from, gossip) ⇒ welcome(joinWith, from, gossip)
    case InitJoin              ⇒ sender() ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒
      becomeUninitialized()
      join(address)
    case JoinSeedNodes(newSeedNodes) ⇒
      becomeUninitialized()
      joinSeedNodes(newSeedNodes)
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick ⇒
      if (deadline.exists(_.isOverdue)) {
        // join attempt failed, retry
        becomeUninitialized()
        if (seedNodes.nonEmpty) joinSeedNodes(seedNodes)
        else join(joinWith)
      }
  }

  def becomeUninitialized(): Unit = {
    // make sure that join process is stopped
    stopSeedNodeProcess()
    context.become(uninitialized)
  }

  def becomeInitialized(): Unit = {
    // start heartbeatSender here, and not in constructor to make sure that
    // heartbeating doesn't start before Welcome is received
    context.actorOf(Props[ClusterHeartbeatSender].
      withDispatcher(UseDispatcher), name = "heartbeatSender")
    // make sure that join process is stopped
    stopSeedNodeProcess()
    context.become(initialized)
  }

  def initialized: Actor.Receive = {
    case msg: GossipEnvelope              ⇒ receiveGossip(msg)
    case msg: GossipStatus                ⇒ receiveGossipStatus(msg)
    case GossipTick                       ⇒ gossipTick()
    case GossipSpeedupTick                ⇒ gossipSpeedupTick()
    case ReapUnreachableTick              ⇒ reapUnreachableMembers()
    case LeaderActionsTick                ⇒ leaderActions()
    case PublishStatsTick                 ⇒ publishInternalStats()
    case InitJoin                         ⇒ initJoin()
    case Join(node, roles)                ⇒ joining(node, roles)
    case ClusterUserAction.Down(address)  ⇒ downing(address)
    case ClusterUserAction.Leave(address) ⇒ leaving(address)
    case SendGossipTo(address)            ⇒ sendGossipTo(address)
    case msg: SubscriptionMessage         ⇒ publisher forward msg
    case QuarantinedEvent(address, uid)   ⇒ quarantined(UniqueAddress(address, uid))
    case ClusterUserAction.JoinTo(address) ⇒
      logInfo("Trying to join [{}] when already part of a cluster, ignoring", address)
    case JoinSeedNodes(seedNodes) ⇒
      logInfo(
        "Trying to join seed nodes [{}] when already part of a cluster, ignoring",
        seedNodes.mkString(", "))
  }

  def removed: Actor.Receive = {
    case msg: SubscriptionMessage ⇒ publisher forward msg
  }

  def receive = uninitialized

  override def unhandled(message: Any): Unit = message match {
    case _: Tick           ⇒
    case _: GossipEnvelope ⇒
    case _: GossipStatus   ⇒
    case other             ⇒ super.unhandled(other)
  }

  def initJoin(): Unit = {
    val selfStatus = latestGossip.member(selfUniqueAddress).status
    if (Gossip.removeUnreachableWithMemberStatus.contains(selfStatus))
      // prevents a Down and Exiting node from being used for joining
      sender() ! InitJoinNack(selfAddress)
    else
      sender() ! InitJoinAck(selfAddress)
  }

  def joinSeedNodes(newSeedNodes: immutable.IndexedSeq[Address]): Unit = {
    if (newSeedNodes.nonEmpty) {
      stopSeedNodeProcess()
      seedNodes = newSeedNodes // keep them for retry
      seedNodeProcess =
        if (newSeedNodes == immutable.IndexedSeq(selfAddress)) {
          self ! ClusterUserAction.JoinTo(selfAddress)
          None
        } else {
          // use unique name of this actor, stopSeedNodeProcess doesn't wait for termination
          seedNodeProcessCounter += 1
          if (newSeedNodes.head == selfAddress) {
            Some(context.actorOf(Props(classOf[FirstSeedNodeProcess], newSeedNodes).
              withDispatcher(UseDispatcher), name = "firstSeedNodeProcess-" + seedNodeProcessCounter))
          } else {
            Some(context.actorOf(Props(classOf[JoinSeedNodeProcess], newSeedNodes).
              withDispatcher(UseDispatcher), name = "joinSeedNodeProcess-" + seedNodeProcessCounter))
          }
        }
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
      log.warning(
        "Trying to join member with wrong protocol, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, address.protocol)
    else if (address.system != selfAddress.system)
      log.warning(
        "Trying to join member with wrong ActorSystem name, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, address.system)
    else {
      require(latestGossip.members.isEmpty, "Join can only be done from empty state")

      // to support manual join when joining to seed nodes is stuck (no seed nodes available)
      stopSeedNodeProcess()

      if (address == selfAddress) {
        becomeInitialized()
        joining(selfUniqueAddress, cluster.selfRoles)
      } else {
        val joinDeadline = RetryUnsuccessfulJoinAfter match {
          case d: FiniteDuration ⇒ Some(Deadline.now + d)
          case _                 ⇒ None
        }
        context.become(tryingToJoin(address, joinDeadline))
        clusterCore(address) ! Join(selfUniqueAddress, cluster.selfRoles)
      }
    }
  }

  def stopSeedNodeProcess(): Unit = {
    seedNodeProcess match {
      case Some(s) ⇒
        // manual join, abort current seedNodeProcess
        context stop s
        seedNodeProcess = None
      case None ⇒ // no seedNodeProcess in progress
    }
  }

  /**
   * State transition to JOINING - new node joining.
   * Received `Join` message and replies with `Welcome` message, containing
   * current gossip state, including the new joining member.
   */
  def joining(node: UniqueAddress, roles: Set[String]): Unit = {
    val selfStatus = latestGossip.member(selfUniqueAddress).status
    if (node.address.protocol != selfAddress.protocol)
      log.warning(
        "Member with wrong protocol tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, node.address.protocol)
    else if (node.address.system != selfAddress.system)
      log.warning(
        "Member with wrong ActorSystem name tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, node.address.system)
    else if (Gossip.removeUnreachableWithMemberStatus.contains(selfStatus))
      logInfo("Trying to join [{}] to [{}] member, ignoring. Use a member that is Up instead.", node, selfStatus)
    else {
      val localMembers = latestGossip.members

      // check by address without uid to make sure that node with same host:port is not allowed
      // to join until previous node with that host:port has been removed from the cluster
      localMembers.find(_.address == node.address) match {
        case Some(m) if m.uniqueAddress == node ⇒
          // node retried join attempt, probably due to lost Welcome message
          logInfo("Existing member [{}] is joining again.", m)
          if (node != selfUniqueAddress)
            sender() ! Welcome(selfUniqueAddress, latestGossip)
        case Some(m) ⇒
          // node restarted, same host:port as existing member, but with different uid
          // safe to down and later remove existing member
          // new node will retry join
          logInfo("New incarnation of existing member [{}] is trying to join. " +
            "Existing will be removed from the cluster and then new member will be allowed to join.", m)
          if (m.status != Down) {
            // we can confirm it as terminated/unreachable immediately
            val newReachability = latestGossip.overview.reachability.terminated(selfUniqueAddress, m.uniqueAddress)
            val newOverview = latestGossip.overview.copy(reachability = newReachability)
            val newGossip = latestGossip.copy(overview = newOverview)
            updateLatestGossip(newGossip)

            downing(m.address)
          }
        case None ⇒
          // remove the node from the failure detector
          failureDetector.remove(node.address)

          // add joining node as Joining
          // add self in case someone else joins before self has joined (Set discards duplicates)
          val newMembers = localMembers + Member(node, roles) + Member(selfUniqueAddress, cluster.selfRoles)
          val newGossip = latestGossip copy (members = newMembers)

          updateLatestGossip(newGossip)

          logInfo("Node [{}] is JOINING, roles [{}]", node.address, roles.mkString(", "))
          if (node == selfUniqueAddress) {
            if (localMembers.isEmpty)
              leaderActions() // important for deterministic oldest when bootstrapping
          } else
            sender() ! Welcome(selfUniqueAddress, latestGossip)

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
      logInfo("Ignoring welcome from [{}] when trying to join with [{}]", from.address, joinWith)
    else {
      logInfo("Welcome from [{}]", from.address)
      latestGossip = gossip seen selfUniqueAddress
      assertLatestGossip()
      publish(latestGossip)
      if (from != selfUniqueAddress)
        gossipTo(from, sender())
      becomeInitialized()
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

      updateLatestGossip(newGossip)

      logInfo("Marked address [{}] as [{}]", address, Leaving)
      publish(latestGossip)
      // immediate gossip to speed up the leaving process
      gossip()
    }
  }

  /**
   * This method is called when a member sees itself as Exiting or Down.
   */
  def shutdown(): Unit = cluster.shutdown()

  /**
   * State transition to DOWN.
   * Its status is set to DOWN. The node is also removed from the `seen` table.
   *
   * The node will eventually be removed by the leader, and only after removal a new node with same address can
   * join the cluster through the normal joining procedure.
   */
  def downing(address: Address): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen
    val localReachability = localOverview.reachability

    // check if the node to DOWN is in the `members` set
    localMembers.find(_.address == address) match {
      case Some(m) if (m.status != Down) ⇒
        if (localReachability.isReachable(m.uniqueAddress))
          logInfo("Marking node [{}] as [{}]", m.address, Down)
        else
          logInfo("Marking unreachable node [{}] as [{}]", m.address, Down)

        // replace member (changed status)
        val newMembers = localMembers - m + m.copy(status = Down)
        // remove nodes marked as DOWN from the `seen` table
        val newSeen = localSeen - m.uniqueAddress

        // update gossip overview
        val newOverview = localOverview copy (seen = newSeen)
        val newGossip = localGossip copy (members = newMembers, overview = newOverview) // update gossip
        updateLatestGossip(newGossip)

        publish(latestGossip)
      case Some(_) ⇒ // already down
      case None ⇒
        logInfo("Ignoring down of unknown node [{}]", address)
    }

  }

  def quarantined(node: UniqueAddress): Unit = {
    val localGossip = latestGossip
    if (localGossip.hasMember(node)) {
      val newReachability = latestGossip.overview.reachability.terminated(selfUniqueAddress, node)
      val newOverview = localGossip.overview copy (reachability = newReachability)
      val newGossip = localGossip copy (overview = newOverview)
      updateLatestGossip(newGossip)
      log.warning(
        "Cluster Node [{}] - Marking node as TERMINATED [{}], due to quarantine. Node roles [{}]",
        selfAddress, node.address, selfRoles.mkString(","))
      publish(latestGossip)
      downing(node.address)
    }
  }

  def receiveGossipStatus(status: GossipStatus): Unit = {
    val from = status.from
    if (!latestGossip.overview.reachability.isReachable(selfUniqueAddress, from))
      logInfo("Ignoring received gossip status from unreachable [{}] ", from)
    else if (latestGossip.members.forall(_.uniqueAddress != from))
      log.debug("Cluster Node [{}] - Ignoring received gossip status from unknown [{}]", selfAddress, from)
    else {
      (status.version compareTo latestGossip.version) match {
        case VectorClock.Same  ⇒ // same version
        case VectorClock.After ⇒ gossipStatusTo(from, sender()) // remote is newer
        case _                 ⇒ gossipTo(from, sender()) // conflicting or local is newer
      }
    }
  }

  /**
   * The types of gossip actions that receive gossip has performed.
   */
  sealed trait ReceiveGossipType
  case object Ignored extends ReceiveGossipType
  case object Older extends ReceiveGossipType
  case object Newer extends ReceiveGossipType
  case object Same extends ReceiveGossipType
  case object Merge extends ReceiveGossipType

  /**
   * Receive new gossip.
   */
  def receiveGossip(envelope: GossipEnvelope): ReceiveGossipType = {
    val from = envelope.from
    val remoteGossip = envelope.gossip
    val localGossip = latestGossip

    if (remoteGossip eq Gossip.empty) {
      log.debug("Cluster Node [{}] - Ignoring received gossip from [{}] to protect against overload", selfAddress, from)
      Ignored
    } else if (envelope.to != selfUniqueAddress) {
      logInfo("Ignoring received gossip intended for someone else, from [{}] to [{}]", from.address, envelope.to)
      Ignored
    } else if (!localGossip.overview.reachability.isReachable(selfUniqueAddress, from)) {
      logInfo("Ignoring received gossip from unreachable [{}] ", from)
      Ignored
    } else if (localGossip.members.forall(_.uniqueAddress != from)) {
      log.debug("Cluster Node [{}] - Ignoring received gossip from unknown [{}]", selfAddress, from)
      Ignored
    } else if (remoteGossip.members.forall(_.uniqueAddress != selfUniqueAddress)) {
      logInfo("Ignoring received gossip that does not contain myself, from [{}]", from)
      Ignored
    } else {
      val comparison = remoteGossip.version compareTo localGossip.version

      val (winningGossip, talkback, gossipType) = comparison match {
        case VectorClock.Same ⇒
          // same version
          (remoteGossip mergeSeen localGossip, !remoteGossip.seenByNode(selfUniqueAddress), Same)
        case VectorClock.Before ⇒
          // local is newer
          (localGossip, true, Older)
        case VectorClock.After ⇒
          // remote is newer
          (remoteGossip, !remoteGossip.seenByNode(selfUniqueAddress), Newer)
        case _ ⇒
          // conflicting versions, merge
          // We can see that a removal was done when it is not in one of the gossips has status
          // Down or Exiting in the other gossip.
          // Perform the same pruning (clear of VectorClock) as the leader did when removing a member.
          // Removal of member itself is handled in merge (pickHighestPriority)
          val prunedLocalGossip = localGossip.members.foldLeft(localGossip) { (g, m) ⇒
            if (Gossip.removeUnreachableWithMemberStatus(m.status) && !remoteGossip.members.contains(m)) {
              log.debug("Cluster Node [{}] - Pruned conflicting local gossip: {}", selfAddress, m)
              g.prune(VectorClock.Node(vclockName(m.uniqueAddress)))
            } else
              g
          }
          val prunedRemoteGossip = remoteGossip.members.foldLeft(remoteGossip) { (g, m) ⇒
            if (Gossip.removeUnreachableWithMemberStatus(m.status) && !localGossip.members.contains(m)) {
              log.debug("Cluster Node [{}] - Pruned conflicting remote gossip: {}", selfAddress, m)
              g.prune(VectorClock.Node(vclockName(m.uniqueAddress)))
            } else
              g
          }

          (prunedRemoteGossip merge prunedLocalGossip, true, Merge)
      }

      latestGossip = winningGossip seen selfUniqueAddress
      assertLatestGossip()

      // for all new joining nodes we remove them from the failure detector
      latestGossip.members foreach {
        node ⇒ if (node.status == Joining && !localGossip.members(node)) failureDetector.remove(node.address)
      }

      log.debug("Cluster Node [{}] - Receiving gossip from [{}]", selfAddress, from)

      if (comparison == VectorClock.Concurrent) {
        log.debug(
          """Couldn't establish a causal relationship between "remote" gossip and "local" gossip - Remote[{}] - Local[{}] - merged them into [{}]""",
          remoteGossip, localGossip, winningGossip)
      }

      if (statsEnabled) {
        gossipStats = gossipType match {
          case Merge   ⇒ gossipStats.incrementMergeCount
          case Same    ⇒ gossipStats.incrementSameCount
          case Newer   ⇒ gossipStats.incrementNewerCount
          case Older   ⇒ gossipStats.incrementOlderCount
          case Ignored ⇒ gossipStats // included in receivedGossipCount
        }
      }

      publish(latestGossip)

      val selfStatus = latestGossip.member(selfUniqueAddress).status
      if (selfStatus == Exiting)
        shutdown()
      else if (talkback) {
        // send back gossip to sender() when sender() had different view, i.e. merge, or sender() had
        // older or sender() had newer
        gossipTo(from, sender())
      }
      gossipType
    }
  }

  def gossipTick(): Unit = {
    gossip()
    if (isGossipSpeedupNeeded) {
      scheduler.scheduleOnce(GossipInterval / 3, self, GossipSpeedupTick)
      scheduler.scheduleOnce(GossipInterval * 2 / 3, self, GossipSpeedupTick)
    }
  }

  def gossipSpeedupTick(): Unit =
    if (isGossipSpeedupNeeded) gossip()

  def isGossipSpeedupNeeded: Boolean =
    (latestGossip.overview.seen.size < latestGossip.members.size / 2)

  /**
   * Sends full gossip to `n` other random members.
   */
  def gossipRandomN(n: Int): Unit = {
    if (!isSingletonCluster && n > 0) {
      val localGossip = latestGossip
      // using ArrayList to be able to shuffle
      val possibleTargets = new ArrayList[UniqueAddress](localGossip.members.size)
      localGossip.members.foreach { m ⇒
        if (validNodeForGossip(m.uniqueAddress))
          possibleTargets.add(m.uniqueAddress)
      }
      val randomTargets =
        if (possibleTargets.size <= n)
          possibleTargets
        else {
          Collections.shuffle(possibleTargets, ThreadLocalRandom.current())
          possibleTargets.subList(0, n)
        }

      val iter = randomTargets.iterator
      while (iter.hasNext)
        gossipTo(iter.next())
    }
  }

  /**
   * Initiates a new round of gossip.
   */
  def gossip(): Unit = {

    if (!isSingletonCluster) {
      val localGossip = latestGossip

      val preferredGossipTargets: Vector[UniqueAddress] =
        if (ThreadLocalRandom.current.nextDouble() < adjustedGossipDifferentViewProbability) {
          // If it's time to try to gossip to some nodes with a different view
          // gossip to a random alive member with preference to a member with older gossip version
          localGossip.members.collect {
            case m if !localGossip.seenByNode(m.uniqueAddress) && validNodeForGossip(m.uniqueAddress) ⇒
              m.uniqueAddress
          }(breakOut)
        } else Vector.empty

      if (preferredGossipTargets.nonEmpty) {
        val peer = selectRandomNode(preferredGossipTargets)
        // send full gossip because it has different view
        peer foreach gossipTo
      } else {
        // Fall back to localGossip; important to not accidentally use `map` of the SortedSet, since the original order is not preserved)
        val peer = selectRandomNode(localGossip.members.toIndexedSeq.collect {
          case m if validNodeForGossip(m.uniqueAddress) ⇒ m.uniqueAddress
        })
        peer foreach { node ⇒
          if (localGossip.seenByNode(node)) gossipStatusTo(node)
          else gossipTo(node)
        }
      }
    }
  }

  /**
   * For large clusters we should avoid shooting down individual
   * nodes. Therefore the probability is reduced for large clusters.
   */
  def adjustedGossipDifferentViewProbability: Double = {
    val size = latestGossip.members.size
    val low = ReduceGossipDifferentViewProbability
    val high = low * 3
    // start reduction when cluster is larger than configured ReduceGossipDifferentViewProbability
    if (size <= low)
      GossipDifferentViewProbability
    else {
      // don't go lower than 1/10 of the configured GossipDifferentViewProbability
      val minP = GossipDifferentViewProbability / 10
      if (size >= high)
        minP
      else {
        // linear reduction of the probability with increasing number of nodes
        // from ReduceGossipDifferentViewProbability at ReduceGossipDifferentViewProbability nodes
        // to ReduceGossipDifferentViewProbability / 10 at ReduceGossipDifferentViewProbability * 3 nodes
        // i.e. default from 0.8 at 400 nodes, to 0.08 at 1600 nodes
        val k = (minP - GossipDifferentViewProbability) / (high - low)
        GossipDifferentViewProbability + (size - low) * k
      }
    }
  }

  /**
   * Runs periodic leader actions, such as member status transitions, assigning partitions etc.
   */
  def leaderActions(): Unit = {
    if (latestGossip.isLeader(selfUniqueAddress, selfUniqueAddress)) {
      // only run the leader actions if we are the LEADER
      val firstNotice = 20
      val periodicNotice = 60
      if (latestGossip.convergence(selfUniqueAddress)) {
        if (leaderActionCounter >= firstNotice)
          logInfo("Leader can perform its duties again")
        leaderActionCounter = 0
        leaderActionsOnConvergence()
      } else {
        if (cluster.settings.AllowWeaklyUpMembers)
          moveJoiningToWeaklyUp()

        leaderActionCounter += 1
        if (leaderActionCounter == firstNotice || leaderActionCounter % periodicNotice == 0)
          logInfo(
            "Leader can currently not perform its duties, reachability status: [{}], member status: [{}]",
            latestGossip.reachabilityExcludingDownedObservers,
            latestGossip.members.map(m ⇒
              s"${m.address} ${m.status} seen=${latestGossip.seenByNode(m.uniqueAddress)}").mkString(", "))
      }
    }
    shutdownSelfWhenDown()
  }

  def shutdownSelfWhenDown(): Unit = {
    if (latestGossip.member(selfUniqueAddress).status == Down) {
      // When all reachable have seen the state this member will shutdown itself when it has
      // status Down. The down commands should spread before we shutdown.
      val unreachable = latestGossip.overview.reachability.allUnreachableOrTerminated
      val downed = latestGossip.members.collect { case m if m.status == Down ⇒ m.uniqueAddress }
      if (downed.forall(node ⇒ unreachable(node) || latestGossip.seenByNode(node))) {
        // the reason for not shutting down immediately is to give the gossip a chance to spread
        // the downing information to other downed nodes, so that they can shutdown themselves
        logInfo("Shutting down myself")
        // not crucial to send gossip, but may speedup removal since fallback to failure detection is not needed
        // if other downed know that this node has seen the version
        gossipRandomN(MaxGossipsBeforeShuttingDownMyself)
        shutdown()
      }
    }
  }

  def isMinNrOfMembersFulfilled: Boolean = {
    latestGossip.members.size >= MinNrOfMembers && MinNrOfMembersOfRole.forall {
      case (role, threshold) ⇒ latestGossip.members.count(_.hasRole(role)) >= threshold
    }
  }

  /**
   * Leader actions are as follows:
   * 1. Move JOINING     => UP                   -- When a node joins the cluster
   * 2. Move LEAVING     => EXITING              --
   * 3. Non-exiting remain                       --
   * 4. Move unreachable EXITING => REMOVED      -- When all nodes have seen the EXITING node as unreachable (convergence) -
   *                                                remove the node from the node ring and seen table
   * 5. Move unreachable DOWN/EXITING => REMOVED -- When all nodes have seen that the node is DOWN/EXITING (convergence) -
   *                                                remove the node from the node ring and seen table
   * 7. Updating the vclock version for the changes
   * 8. Updating the `seen` table
   * 9. Update the state with the new gossip
   */
  def leaderActionsOnConvergence(): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen

    val enoughMembers: Boolean = isMinNrOfMembersFulfilled
    def isJoiningToUp(m: Member): Boolean = (m.status == Joining || m.status == WeaklyUp) && enoughMembers

    val removedUnreachable = for {
      node ← localOverview.reachability.allUnreachableOrTerminated
      m = localGossip.member(node)
      if Gossip.removeUnreachableWithMemberStatus(m.status)
    } yield m

    val changedMembers = localMembers collect {
      var upNumber = 0

      {
        case m if isJoiningToUp(m) ⇒
          // Move JOINING => UP (once all nodes have seen that this node is JOINING, i.e. we have a convergence)
          // and minimum number of nodes have joined the cluster
          if (upNumber == 0) {
            // It is alright to use same upNumber as already used by a removed member, since the upNumber
            // is only used for comparing age of current cluster members (Member.isOlderThan)
            val youngest = localGossip.youngestMember
            upNumber = 1 + (if (youngest.upNumber == Int.MaxValue) 0 else youngest.upNumber)
          } else {
            upNumber += 1
          }
          m.copyUp(upNumber)

        case m if m.status == Leaving ⇒
          // Move LEAVING => EXITING (once we have a convergence on LEAVING)
          m copy (status = Exiting)
      }
    }

    if (removedUnreachable.nonEmpty || changedMembers.nonEmpty) {
      // handle changes

      // replace changed members
      val newMembers = changedMembers union localMembers diff removedUnreachable

      // removing REMOVED nodes from the `seen` table
      val removed = removedUnreachable.map(_.uniqueAddress)
      val newSeen = localSeen diff removed
      // removing REMOVED nodes from the `reachability` table
      val newReachability = localOverview.reachability.remove(removed)
      val newOverview = localOverview copy (seen = newSeen, reachability = newReachability)
      // Clear the VectorClock when member is removed. The change made by the leader is stamped
      // and will propagate as is if there are no other changes on other nodes.
      // If other concurrent changes on other nodes (e.g. join) the pruning is also
      // taken care of when receiving gossips.
      val newVersion = removed.foldLeft(localGossip.version) { (v, node) ⇒
        v.prune(VectorClock.Node(vclockName(node)))
      }
      val newGossip = localGossip copy (members = newMembers, overview = newOverview, version = newVersion)

      updateLatestGossip(newGossip)

      // log status changes
      changedMembers foreach { m ⇒
        logInfo("Leader is moving node [{}] to [{}]", m.address, m.status)
      }

      // log the removal of the unreachable nodes
      removedUnreachable foreach { m ⇒
        val status = if (m.status == Exiting) "exiting" else "unreachable"
        logInfo("Leader is removing {} node [{}]", status, m.address)
      }

      publish(latestGossip)

      if (latestGossip.member(selfUniqueAddress).status == Exiting) {
        // Leader is moving itself from Leaving to Exiting. Let others know (best effort)
        // before shutdown. Otherwise they will not see the Exiting state change
        // and there will not be convergence until they have detected this node as
        // unreachable and the required downing has finished. They will still need to detect
        // unreachable, but Exiting unreachable will be removed without downing, i.e.
        // normally the leaving of a leader will be graceful without the need
        // for downing. However, if those final gossip messages never arrive it is
        // alright to require the downing, because that is probably caused by a
        // network failure anyway.
        gossipRandomN(NumberOfGossipsBeforeShutdownWhenLeaderExits)
        shutdown()
      }

    }
  }

  def moveJoiningToWeaklyUp(): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members

    val enoughMembers: Boolean = isMinNrOfMembersFulfilled
    def isJoiningToWeaklyUp(m: Member): Boolean =
      m.status == Joining && enoughMembers && latestGossip.reachabilityExcludingDownedObservers.isReachable(m.uniqueAddress)
    val changedMembers = localMembers.collect {
      case m if isJoiningToWeaklyUp(m) ⇒ m.copy(status = WeaklyUp)
    }

    if (changedMembers.nonEmpty) {
      // replace changed members
      val newMembers = changedMembers union localMembers
      val newGossip = localGossip.copy(members = newMembers)
      updateLatestGossip(newGossip)

      // log status changes
      changedMembers foreach { m ⇒
        logInfo("Leader is moving node [{}] to [{}]", m.address, m.status)
      }

      publish(latestGossip)
    }

  }

  /**
   * Reaps the unreachable members according to the failure detector's verdict.
   */
  def reapUnreachableMembers(): Unit = {
    if (!isSingletonCluster) {
      // only scrutinize if we are a non-singleton cluster

      val localGossip = latestGossip
      val localOverview = localGossip.overview
      val localMembers = localGossip.members

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒
        member.uniqueAddress == selfUniqueAddress ||
          localOverview.reachability.status(selfUniqueAddress, member.uniqueAddress) == Reachability.Unreachable ||
          localOverview.reachability.status(selfUniqueAddress, member.uniqueAddress) == Reachability.Terminated ||
          failureDetector.isAvailable(member.address)
      }

      val newlyDetectedReachableMembers = localOverview.reachability.allUnreachableFrom(selfUniqueAddress) collect {
        case node if node != selfUniqueAddress && failureDetector.isAvailable(node.address) ⇒
          localGossip.member(node)
      }

      if (newlyDetectedUnreachableMembers.nonEmpty || newlyDetectedReachableMembers.nonEmpty) {

        val newReachability1 = (localOverview.reachability /: newlyDetectedUnreachableMembers) {
          (reachability, m) ⇒ reachability.unreachable(selfUniqueAddress, m.uniqueAddress)
        }
        val newReachability2 = (newReachability1 /: newlyDetectedReachableMembers) {
          (reachability, m) ⇒ reachability.reachable(selfUniqueAddress, m.uniqueAddress)
        }

        if (newReachability2 ne localOverview.reachability) {
          val newOverview = localOverview copy (reachability = newReachability2)
          val newGossip = localGossip copy (overview = newOverview)

          updateLatestGossip(newGossip)

          val (exiting, nonExiting) = newlyDetectedUnreachableMembers.partition(_.status == Exiting)
          if (nonExiting.nonEmpty)
            log.warning("Cluster Node [{}] - Marking node(s) as UNREACHABLE [{}]. Node roles [{}]", selfAddress, nonExiting.mkString(", "), selfRoles.mkString(", "))
          if (exiting.nonEmpty)
            logInfo(
              "Marking exiting node(s) as UNREACHABLE [{}]. This is expected and they will be removed.",
              exiting.mkString(", "))
          if (newlyDetectedReachableMembers.nonEmpty)
            logInfo("Marking node(s) as REACHABLE [{}]. Node roles [{}]", newlyDetectedReachableMembers.mkString(", "), selfRoles.mkString(","))

          publish(latestGossip)
        }
      }
    }
  }

  def selectRandomNode(nodes: IndexedSeq[UniqueAddress]): Option[UniqueAddress] =
    if (nodes.isEmpty) None
    else Some(nodes(ThreadLocalRandom.current nextInt nodes.size))

  def isSingletonCluster: Boolean = latestGossip.isSingletonCluster

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
    if (validNodeForGossip(node))
      clusterCore(node.address) ! GossipEnvelope(selfUniqueAddress, node, latestGossip)

  def gossipTo(node: UniqueAddress, destination: ActorRef): Unit =
    if (validNodeForGossip(node))
      destination ! GossipEnvelope(selfUniqueAddress, node, latestGossip)

  def gossipStatusTo(node: UniqueAddress, destination: ActorRef): Unit =
    if (validNodeForGossip(node))
      destination ! GossipStatus(selfUniqueAddress, latestGossip.version)

  def gossipStatusTo(node: UniqueAddress): Unit =
    if (validNodeForGossip(node))
      clusterCore(node.address) ! GossipStatus(selfUniqueAddress, latestGossip.version)

  def validNodeForGossip(node: UniqueAddress): Boolean =
    (node != selfUniqueAddress && latestGossip.hasMember(node) &&
      latestGossip.reachabilityExcludingDownedObservers.isReachable(node))

  def updateLatestGossip(newGossip: Gossip): Unit = {
    // Updating the vclock version for the changes
    val versionedGossip = newGossip :+ vclockNode
    // Nobody else have seen this gossip but us
    val seenVersionedGossip = versionedGossip onlySeen (selfUniqueAddress)
    // Update the state with the new gossip
    latestGossip = seenVersionedGossip
    assertLatestGossip()
  }

  def assertLatestGossip(): Unit =
    if (Cluster.isAssertInvariantsEnabled && latestGossip.version.versions.size > latestGossip.members.size)
      throw new IllegalStateException(s"Too many vector clock entries in gossip state ${latestGossip}")

  def publish(newGossip: Gossip): Unit = {
    publisher ! PublishChanges(newGossip)
    if (PublishStatsInterval == Duration.Zero) publishInternalStats()
  }

  def publishInternalStats(): Unit = {
    val vclockStats = VectorClockStats(
      versionSize = latestGossip.version.versions.size,
      seenLatest = latestGossip.members.count(m ⇒ latestGossip.seenByNode(m.uniqueAddress)))
    publisher ! CurrentInternalStats(gossipStats, vclockStats)
  }

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
 * The supplied callback will be run, once, when current cluster member come up with the same status.
 */
private[cluster] class OnMemberStatusChangedListener(callback: Runnable, status: MemberStatus) extends Actor with ActorLogging {
  import ClusterEvent._
  private val cluster = Cluster(context.system)
  private val to = status match {
    case Up      ⇒ classOf[MemberUp]
    case Removed ⇒ classOf[MemberRemoved]
    case other ⇒ throw new IllegalArgumentException(
      s"Expected Up or Removed in OnMemberStatusChangedListener, got [$other]")
  }

  override def preStart(): Unit =
    cluster.subscribe(self, to)

  override def postStop(): Unit = {
    if (status == Removed)
      done()
    cluster.unsubscribe(self)
  }

  def receive = {
    case state: CurrentClusterState ⇒
      if (state.members.exists(isTriggered))
        done()
    case MemberUp(member) ⇒
      if (isTriggered(member))
        done()
    case MemberRemoved(member, _) ⇒
      if (isTriggered(member))
        done()
  }

  private def done(): Unit = {
    try callback.run() catch {
      case NonFatal(e) ⇒ log.error(e, "[{}] callback failed with [{}]", s"On${to.getSimpleName}", e.getMessage)
    } finally {
      context stop self
    }
  }

  private def isTriggered(m: Member): Boolean =
    m.uniqueAddress == cluster.selfUniqueAddress && m.status == status

}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[cluster] final case class GossipStats(
  receivedGossipCount: Long = 0L,
  mergeCount:          Long = 0L,
  sameCount:           Long = 0L,
  newerCount:          Long = 0L,
  olderCount:          Long = 0L) {

  def incrementMergeCount(): GossipStats =
    copy(mergeCount = mergeCount + 1, receivedGossipCount = receivedGossipCount + 1)

  def incrementSameCount(): GossipStats =
    copy(sameCount = sameCount + 1, receivedGossipCount = receivedGossipCount + 1)

  def incrementNewerCount(): GossipStats =
    copy(newerCount = newerCount + 1, receivedGossipCount = receivedGossipCount + 1)

  def incrementOlderCount(): GossipStats =
    copy(olderCount = olderCount + 1, receivedGossipCount = receivedGossipCount + 1)

  def :+(that: GossipStats): GossipStats = {
    GossipStats(
      this.receivedGossipCount + that.receivedGossipCount,
      this.mergeCount + that.mergeCount,
      this.sameCount + that.sameCount,
      this.newerCount + that.newerCount,
      this.olderCount + that.olderCount)
  }

  def :-(that: GossipStats): GossipStats = {
    GossipStats(
      this.receivedGossipCount - that.receivedGossipCount,
      this.mergeCount - that.mergeCount,
      this.sameCount - that.sameCount,
      this.newerCount - that.newerCount,
      this.olderCount - that.olderCount)
  }

}

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[cluster] final case class VectorClockStats(
  versionSize: Int = 0,
  seenLatest:  Int = 0)

