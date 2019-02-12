/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.actor._
import akka.annotation.InternalApi
import akka.actor.SupervisorStrategy.Stop
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.Done
import akka.actor.CoordinatedShutdown.Reason
import akka.pattern.ask
import akka.remote.QuarantinedEvent
import akka.util.Timeout
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

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
@InternalApi
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
@InternalApi
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

  sealed trait ConfigCheck
  case object UncheckedConfig extends ConfigCheck
  case object IncompatibleConfig extends ConfigCheck
  /**
   * Node with version 2.5.9 or earlier is joining. The serialized
   * representation of `InitJoinAck` must be a plain `Address` for
   * such a joining node.
   */
  case object ConfigCheckUnsupportedByJoiningNode extends ConfigCheck

  final case class CompatibleConfig(clusterConfig: Config) extends ConfigCheck

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  case class InitJoin(configOfJoiningNode: Config) extends ClusterMessage with DeadLetterSuppression

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  final case class InitJoinAck(address: Address, configCheck: ConfigCheck) extends ClusterMessage with DeadLetterSuppression

  /**
   * see JoinSeedNode
   */
  @SerialVersionUID(1L)
  final case class InitJoinNack(address: Address) extends ClusterMessage with DeadLetterSuppression

  final case class ExitingConfirmed(node: UniqueAddress) extends ClusterMessage with DeadLetterSuppression

  /**
   * Marker interface for periodic tick messages
   */
  sealed trait Tick

  case object GossipTick extends Tick

  case object GossipSpeedupTick extends Tick

  case object ReapUnreachableTick extends Tick

  case object LeaderActionsTick extends Tick

  case object PublishStatsTick extends Tick

  final case class SendGossipTo(address: Address)

  case object GetClusterCoreRef

  /**
   * Command to [[akka.cluster.ClusterDaemon]] to create a
   * [[akka.cluster.OnMemberStatusChangedListener]].
   */
  final case class AddOnMemberUpListener(callback: Runnable) extends NoSerializationVerificationNeeded

  final case class AddOnMemberRemovedListener(callback: Runnable) extends NoSerializationVerificationNeeded

  sealed trait SubscriptionMessage
  final case class Subscribe(subscriber: ActorRef, initialStateMode: SubscriptionInitialStateMode,
                             to: Set[Class[_]]) extends SubscriptionMessage
  final case class Unsubscribe(subscriber: ActorRef, to: Option[Class[_]])
    extends SubscriptionMessage with DeadLetterSuppression
  /**
   * @param receiver [[akka.cluster.ClusterEvent.CurrentClusterState]] will be sent to the `receiver`
   */
  final case class SendCurrentClusterState(receiver: ActorRef) extends SubscriptionMessage

  sealed trait PublishMessage
  final case class PublishChanges(state: MembershipState) extends PublishMessage
  final case class PublishEvent(event: ClusterDomainEvent) extends PublishMessage

  final case object ExitingCompleted

}

/**
 * INTERNAL API.
 *
 * Supervisor managing the different Cluster daemons.
 */
@InternalApi
private[cluster] final class ClusterDaemon(joinConfigCompatChecker: JoinConfigCompatChecker) extends Actor
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._
  // Important - don't use Cluster(context.system) in constructor because that would
  // cause deadlock. The Cluster extension is currently being created and is waiting
  // for response from GetClusterCoreRef in its constructor.
  // Child actors are therefore created when GetClusterCoreRef is received
  var coreSupervisor: Option[ActorRef] = None

  val clusterShutdown = Promise[Done]()
  val coordShutdown = CoordinatedShutdown(context.system)
  coordShutdown.addTask(CoordinatedShutdown.PhaseClusterLeave, "leave") {
    val sys = context.system
    () ⇒
      if (Cluster(sys).isTerminated || Cluster(sys).selfMember.status == Down)
        Future.successful(Done)
      else {
        implicit val timeout = Timeout(coordShutdown.timeout(CoordinatedShutdown.PhaseClusterLeave))
        self.ask(CoordinatedShutdownLeave.LeaveReq).mapTo[Done]
      }
  }
  coordShutdown.addTask(CoordinatedShutdown.PhaseClusterShutdown, "wait-shutdown") { () ⇒
    clusterShutdown.future
  }

  override def postStop(): Unit = {
    clusterShutdown.trySuccess(Done)
    if (Cluster(context.system).settings.RunCoordinatedShutdownWhenDown) {
      // if it was stopped due to leaving CoordinatedShutdown was started earlier
      coordShutdown.run(CoordinatedShutdown.ClusterDowningReason)
    }
  }

  def createChildren(): Unit = {
    coreSupervisor = Some(context.actorOf(Props(classOf[ClusterCoreSupervisor], joinConfigCompatChecker).
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
    case CoordinatedShutdownLeave.LeaveReq ⇒
      val ref = context.actorOf(CoordinatedShutdownLeave.props().withDispatcher(context.props.dispatcher))
      // forward the ask request
      ref.forward(CoordinatedShutdownLeave.LeaveReq)
  }

}

/**
 * INTERNAL API.
 *
 * ClusterCoreDaemon and ClusterDomainEventPublisher can't be restarted because the state
 * would be obsolete. Shutdown the member if any those actors crashed.
 */
@InternalApi
private[cluster] final class ClusterCoreSupervisor(joinConfigCompatChecker: JoinConfigCompatChecker) extends Actor
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  // Important - don't use Cluster(context.system) in constructor because that would
  // cause deadlock. The Cluster extension is currently being created and is waiting
  // for response from GetClusterCoreRef in its constructor.
  // Child actors are therefore created when GetClusterCoreRef is received

  var coreDaemon: Option[ActorRef] = None

  def createChildren(): Unit = {
    val publisher = context.actorOf(Props[ClusterDomainEventPublisher].
      withDispatcher(context.props.dispatcher), name = "publisher")
    coreDaemon = Some(context.watch(context.actorOf(Props(classOf[ClusterCoreDaemon], publisher, joinConfigCompatChecker).
      withDispatcher(context.props.dispatcher), name = "daemon")))
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case NonFatal(e) ⇒
        Cluster(context.system).ClusterLogger.logError(
          e, "crashed, [{}] - shutting down...", e.getMessage)
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
@InternalApi
private[cluster] object ClusterCoreDaemon {
  val NumberOfGossipsBeforeShutdownWhenLeaderExits = 5
  val MaxGossipsBeforeShuttingDownMyself = 5
  val MaxTicksBeforeShuttingDownMyself = 4

}

/**
 * INTERNAL API.
 */
@InternalApi
private[cluster] class ClusterCoreDaemon(publisher: ActorRef, joinConfigCompatChecker: JoinConfigCompatChecker) extends Actor
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._
  import ClusterCoreDaemon._
  import MembershipState._

  val cluster = Cluster(context.system)
  import cluster.ClusterLogger._
  import cluster.{ selfAddress, selfRoles, scheduler, failureDetector, crossDcFailureDetector }
  import cluster.settings._

  val selfDc = cluster.selfDataCenter

  protected def selfUniqueAddress = cluster.selfUniqueAddress

  val vclockNode = VectorClock.Node(Gossip.vclockName(selfUniqueAddress))
  val gossipTargetSelector = new GossipTargetSelector(
    ReduceGossipDifferentViewProbability,
    cluster.settings.MultiDataCenter.CrossDcGossipProbability)

  // note that self is not initially member,
  // and the Gossip is not versioned for this 'Node' yet
  var membershipState = MembershipState(
    Gossip.empty,
    cluster.selfUniqueAddress,
    cluster.settings.SelfDataCenter,
    cluster.settings.MultiDataCenter.CrossDcConnections)

  var isCurrentlyLeader = false

  def latestGossip: Gossip = membershipState.latestGossip

  val statsEnabled = PublishStatsInterval.isFinite
  var gossipStats = GossipStats()

  var seedNodes = SeedNodes
  var seedNodeProcess: Option[ActorRef] = None
  var seedNodeProcessCounter = 0 // for unique names
  var joinSeedNodesDeadline: Option[Deadline] = None
  var leaderActionCounter = 0
  var selfDownCounter = 0

  var exitingTasksInProgress = false
  val selfExiting = Promise[Done]()
  val coordShutdown = CoordinatedShutdown(context.system)
  coordShutdown.addTask(CoordinatedShutdown.PhaseClusterExiting, "wait-exiting") { () ⇒
    if (latestGossip.members.isEmpty)
      Future.successful(Done) // not joined yet
    else
      selfExiting.future
  }
  coordShutdown.addTask(CoordinatedShutdown.PhaseClusterExitingDone, "exiting-completed") {
    val sys = context.system
    () ⇒
      if (Cluster(sys).isTerminated || Cluster(sys).selfMember.status == Down)
        Future.successful(Done)
      else {
        implicit val timeout = Timeout(coordShutdown.timeout(CoordinatedShutdown.PhaseClusterExitingDone))
        self.ask(ExitingCompleted).mapTo[Done]
      }
  }
  var exitingConfirmed = Set.empty[UniqueAddress]

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

    if (seedNodes.isEmpty) {
      if (isClusterBootstrapUsed)
        logDebug("Cluster Bootstrap is used for joining")
      else
        logInfo("No seed-nodes configured, manual cluster join required, see " +
          "https://doc.akka.io/docs/akka/current/cluster-usage.html#joining-to-seed-nodes")
    } else {
      self ! JoinSeedNodes(seedNodes)
    }
  }

  private def isClusterBootstrapUsed: Boolean = {
    val conf = context.system.settings.config
    conf.hasPath("akka.management.cluster.bootstrap") &&
      conf.hasPath("akka.management.http.route-providers") &&
      conf.getStringList("akka.management.http.route-providers")
      .contains("akka.management.cluster.bootstrap.ClusterBootstrap$")
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
    gossipTask.cancel()
    failureDetectorReaperTask.cancel()
    leaderActionsTask.cancel()
    publishStatsTask foreach { _.cancel() }
    selfExiting.trySuccess(Done)
  }

  def uninitialized: Actor.Receive = ({
    case InitJoin ⇒
      logInfo("Received InitJoin message from [{}], but this node is not initialized yet", sender())
      sender() ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒
      join(address)
    case JoinSeedNodes(newSeedNodes) ⇒
      resetJoinSeedNodesDeadline()
      joinSeedNodes(newSeedNodes)
    case msg: SubscriptionMessage ⇒
      publisher forward msg
    case Welcome(from, gossip) ⇒
      welcome(from.address, from, gossip)
    case _: Tick ⇒
      if (joinSeedNodesDeadline.exists(_.isOverdue))
        joinSeedNodesWasUnsuccessful()
  }: Actor.Receive).orElse(receiveExitingCompleted)

  def tryingToJoin(joinWith: Address, deadline: Option[Deadline]): Actor.Receive = ({
    case Welcome(from, gossip) ⇒
      welcome(joinWith, from, gossip)
    case InitJoin ⇒
      logInfo("Received InitJoin message from [{}], but this node is not a member yet", sender())
      sender() ! InitJoinNack(selfAddress)
    case ClusterUserAction.JoinTo(address) ⇒
      becomeUninitialized()
      join(address)
    case JoinSeedNodes(newSeedNodes) ⇒
      resetJoinSeedNodesDeadline()
      becomeUninitialized()
      joinSeedNodes(newSeedNodes)
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick ⇒
      if (joinSeedNodesDeadline.exists(_.isOverdue))
        joinSeedNodesWasUnsuccessful()
      else if (deadline.exists(_.isOverdue)) {
        // join attempt failed, retry
        becomeUninitialized()
        if (seedNodes.nonEmpty) joinSeedNodes(seedNodes)
        else join(joinWith)
      }
  }: Actor.Receive).orElse(receiveExitingCompleted)

  private def resetJoinSeedNodesDeadline(): Unit = {
    joinSeedNodesDeadline = ShutdownAfterUnsuccessfulJoinSeedNodes match {
      case d: FiniteDuration ⇒ Some(Deadline.now + d)
      case _                 ⇒ None // off
    }
  }

  private def joinSeedNodesWasUnsuccessful(): Unit = {
    logWarning(
      "Joining of seed-nodes [{}] was unsuccessful after configured " +
        "shutdown-after-unsuccessful-join-seed-nodes [{}]. Running CoordinatedShutdown.",
      seedNodes.mkString(", "), ShutdownAfterUnsuccessfulJoinSeedNodes)
    joinSeedNodesDeadline = None
    CoordinatedShutdown(context.system).run(CoordinatedShutdown.ClusterDowningReason)
  }

  def becomeUninitialized(): Unit = {
    // make sure that join process is stopped
    stopSeedNodeProcess()
    context.become(uninitialized)
  }

  def becomeInitialized(): Unit = {
    // start heartbeatSender here, and not in constructor to make sure that
    // heartbeating doesn't start before Welcome is received
    val internalHeartbeatSenderProps = Props(new ClusterHeartbeatSender()).withDispatcher(UseDispatcher)
    context.actorOf(internalHeartbeatSenderProps, name = "heartbeatSender")

    val externalHeartbeatProps = Props(new CrossDcHeartbeatSender()).withDispatcher(UseDispatcher)
    context.actorOf(externalHeartbeatProps, name = "crossDcHeartbeatSender")

    // make sure that join process is stopped
    stopSeedNodeProcess()
    joinSeedNodesDeadline = None
    context.become(initialized)
  }

  def initialized: Actor.Receive = ({
    case msg: GossipEnvelope ⇒ receiveGossip(msg)
    case msg: GossipStatus   ⇒ receiveGossipStatus(msg)
    case GossipTick          ⇒ gossipTick()
    case GossipSpeedupTick   ⇒ gossipSpeedupTick()
    case ReapUnreachableTick ⇒ reapUnreachableMembers()
    case LeaderActionsTick   ⇒ leaderActions()
    case PublishStatsTick    ⇒ publishInternalStats()
    case InitJoin(joiningNodeConfig) ⇒
      logInfo("Received InitJoin message from [{}] to [{}]", sender(), selfAddress)
      initJoin(joiningNodeConfig)
    case Join(node, roles)                ⇒ joining(node, roles)
    case ClusterUserAction.Down(address)  ⇒ downing(address)
    case ClusterUserAction.Leave(address) ⇒ leaving(address)
    case SendGossipTo(address)            ⇒ sendGossipTo(address)
    case msg: SubscriptionMessage         ⇒ publisher forward msg
    case QuarantinedEvent(address, uid)   ⇒ quarantined(UniqueAddress(address, uid))
    case ClusterUserAction.JoinTo(address) ⇒
      logInfo("Trying to join [{}] when already part of a cluster, ignoring", address)
    case JoinSeedNodes(nodes) ⇒
      logInfo(
        "Trying to join seed nodes [{}] when already part of a cluster, ignoring",
        nodes.mkString(", "))
    case ExitingConfirmed(address) ⇒ receiveExitingConfirmed(address)
  }: Actor.Receive).orElse(receiveExitingCompleted)

  def receiveExitingCompleted: Actor.Receive = {
    case ExitingCompleted ⇒
      exitingCompleted()
      sender() ! Done // reply to ask
  }

  def receive = uninitialized

  override def unhandled(message: Any): Unit = message match {
    case _: Tick             ⇒
    case _: GossipEnvelope   ⇒
    case _: GossipStatus     ⇒
    case _: ExitingConfirmed ⇒
    case other               ⇒ super.unhandled(other)
  }

  def initJoin(joiningNodeConfig: Config): Unit = {
    val joiningNodeVersion =
      if (joiningNodeConfig.hasPath("akka.version")) joiningNodeConfig.getString("akka.version")
      else "unknown"
    // When joiningNodeConfig is empty the joining node has version 2.5.9 or earlier.
    val configCheckUnsupportedByJoiningNode = joiningNodeConfig.isEmpty

    val selfStatus = latestGossip.member(selfUniqueAddress).status

    if (removeUnreachableWithMemberStatus.contains(selfStatus)) {
      // prevents a Down and Exiting node from being used for joining
      logInfo("Sending InitJoinNack message from node [{}] to [{}] (version [{}])", selfAddress, sender(),
        joiningNodeVersion)
      sender() ! InitJoinNack(selfAddress)
    } else {
      logInfo("Sending InitJoinAck message from node [{}] to [{}] (version [{}])", selfAddress, sender(),
        joiningNodeVersion)
      // run config compatibility check using config provided by
      // joining node and current (full) config on cluster side

      val configWithoutSensitiveKeys = {
        val allowedConfigPaths = JoinConfigCompatChecker.removeSensitiveKeys(context.system.settings.config, cluster.settings)
        // build a stripped down config instead where sensitive config paths are removed
        // we don't want any check to happen on those keys
        JoinConfigCompatChecker.filterWithKeys(allowedConfigPaths, context.system.settings.config)
      }

      val configCheckReply =
        joinConfigCompatChecker.check(joiningNodeConfig, configWithoutSensitiveKeys) match {
          case Valid ⇒
            if (configCheckUnsupportedByJoiningNode)
              ConfigCheckUnsupportedByJoiningNode
            else {
              val nonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joiningNodeConfig, cluster.settings)
              // Send back to joining node a subset of current configuration
              // containing the keys initially sent by the joining node minus
              // any sensitive keys as defined by this node configuration
              val clusterConfig = JoinConfigCompatChecker.filterWithKeys(nonSensitiveKeys, context.system.settings.config)
              CompatibleConfig(clusterConfig)
            }
          case Invalid(messages) ⇒
            // messages are only logged on the cluster side
            logWarning(
              "Found incompatible settings when [{}] tried to join: {}. " +
                s"Self version [{}], Joining version [$joiningNodeVersion].",
              sender().path.address, messages.mkString(", "),
              context.system.settings.ConfigVersion)
            if (configCheckUnsupportedByJoiningNode)
              ConfigCheckUnsupportedByJoiningNode
            else
              IncompatibleConfig
        }

      sender() ! InitJoinAck(selfAddress, configCheckReply)

    }
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
            Some(context.actorOf(Props(classOf[FirstSeedNodeProcess], newSeedNodes, joinConfigCompatChecker).
              withDispatcher(UseDispatcher), name = "firstSeedNodeProcess-" + seedNodeProcessCounter))
          } else {
            Some(context.actorOf(Props(classOf[JoinSeedNodeProcess], newSeedNodes, joinConfigCompatChecker).
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
      logWarning(
        "Trying to join member with wrong protocol, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, address.protocol)
    else if (address.system != selfAddress.system)
      logWarning(
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
  def joining(joiningNode: UniqueAddress, roles: Set[String]): Unit = {
    val selfStatus = latestGossip.member(selfUniqueAddress).status
    if (joiningNode.address.protocol != selfAddress.protocol)
      logWarning(
        "Member with wrong protocol tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, joiningNode.address.protocol)
    else if (joiningNode.address.system != selfAddress.system)
      logWarning(
        "Member with wrong ActorSystem name tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, joiningNode.address.system)
    else if (removeUnreachableWithMemberStatus.contains(selfStatus))
      logInfo("Trying to join [{}] to [{}] member, ignoring. Use a member that is Up instead.", joiningNode, selfStatus)
    else {
      val localMembers = latestGossip.members

      // check by address without uid to make sure that node with same host:port is not allowed
      // to join until previous node with that host:port has been removed from the cluster
      localMembers.find(_.address == joiningNode.address) match {
        case Some(m) if m.uniqueAddress == joiningNode ⇒
          // node retried join attempt, probably due to lost Welcome message
          logInfo("Existing member [{}] is joining again.", m)
          if (joiningNode != selfUniqueAddress)
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
          failureDetector.remove(joiningNode.address)
          crossDcFailureDetector.remove(joiningNode.address)

          // add joining node as Joining
          // add self in case someone else joins before self has joined (Set discards duplicates)
          val newMembers = localMembers + Member(joiningNode, roles) + Member(selfUniqueAddress, cluster.selfRoles)
          val newGossip = latestGossip copy (members = newMembers)

          updateLatestGossip(newGossip)

          if (joiningNode == selfUniqueAddress) {
            logInfo("Node [{}] is JOINING itself (with roles [{}]) and forming new cluster", joiningNode.address, roles.mkString(", "))
            if (localMembers.isEmpty)
              leaderActions() // important for deterministic oldest when bootstrapping
          } else {
            logInfo("Node [{}] is JOINING, roles [{}]", joiningNode.address, roles.mkString(", "))
            sender() ! Welcome(selfUniqueAddress, latestGossip)
          }

          publishMembershipState()
      }
    }
  }

  /**
   * Accept reply from Join request.
   */
  def welcome(joinWith: Address, from: UniqueAddress, gossip: Gossip): Unit = {
    require(latestGossip.members.isEmpty, "Join can only be done from empty state")
    if (joinWith != from.address)
      logInfo("Ignoring welcome from [{}] when trying to join with [{}]", from.address, joinWith)
    else {
      membershipState = membershipState.copy(latestGossip = gossip).seen()
      logInfo("Welcome from [{}]", from.address)
      assertLatestGossip()
      publishMembershipState()
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
    if (latestGossip.members.exists(m ⇒ m.address == address && (m.status == Joining || m.status == WeaklyUp || m.status == Up))) {
      val newMembers = latestGossip.members map { m ⇒ if (m.address == address) m.copy(status = Leaving) else m } // mark node as LEAVING
      val newGossip = latestGossip copy (members = newMembers)

      updateLatestGossip(newGossip)

      logInfo("Marked address [{}] as [{}]", address, Leaving)
      publishMembershipState()
      // immediate gossip to speed up the leaving process
      gossip()
    }
  }

  def exitingCompleted() = {
    logInfo("Exiting completed")
    // ExitingCompleted sent via CoordinatedShutdown to continue the leaving process.
    exitingTasksInProgress = false
    // mark as seen
    membershipState = membershipState.seen()
    assertLatestGossip()
    publishMembershipState()

    // Let others know (best effort) before shutdown. Otherwise they will not see
    // convergence of the Exiting state until they have detected this node as
    // unreachable and the required downing has finished. They will still need to detect
    // unreachable, but Exiting unreachable will be removed without downing, i.e.
    // normally the leaving of a leader will be graceful without the need
    // for downing. However, if those final gossip messages never arrive it is
    // alright to require the downing, because that is probably caused by a
    // network failure anyway.
    gossipRandomN(NumberOfGossipsBeforeShutdownWhenLeaderExits)

    // send ExitingConfirmed to two potential leaders
    val membersExceptSelf = latestGossip.members.filter(_.uniqueAddress != selfUniqueAddress)

    membershipState.leaderOf(membersExceptSelf) match {
      case Some(node1) ⇒
        clusterCore(node1.address) ! ExitingConfirmed(selfUniqueAddress)
        membershipState.leaderOf(membersExceptSelf.filterNot(_.uniqueAddress == node1)) match {
          case Some(node2) ⇒
            clusterCore(node2.address) ! ExitingConfirmed(selfUniqueAddress)
          case None ⇒ // no more potential leader
        }
      case None ⇒ // no leader
    }

    shutdown()
  }

  def receiveExitingConfirmed(node: UniqueAddress): Unit = {
    logInfo("Exiting confirmed [{}]", node.address)
    exitingConfirmed += node
  }

  def cleanupExitingConfirmed(): Unit = {
    // in case the actual removal was performed by another leader node we
    if (exitingConfirmed.nonEmpty)
      exitingConfirmed = exitingConfirmed.filter(n ⇒ latestGossip.members.exists(_.uniqueAddress == n))
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
    val localReachability = membershipState.dcReachability

    // check if the node to DOWN is in the `members` set
    localMembers.find(_.address == address) match {
      case Some(m) if m.status != Down ⇒
        if (localReachability.isReachable(m.uniqueAddress))
          logInfo("Marking node [{}] as [{}]", m.address, Down)
        else
          logInfo("Marking unreachable node [{}] as [{}]", m.address, Down)

        val newGossip = localGossip.markAsDown(m)
        updateLatestGossip(newGossip)
        publishMembershipState()
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
      logWarning(
        "Marking node as TERMINATED [{}], due to quarantine. Node roles [{}]. " +
          "It must still be marked as down before it's removed.",
        node.address, selfRoles.mkString(","))
      publishMembershipState()
    }
  }

  def receiveGossipStatus(status: GossipStatus): Unit = {
    val from = status.from
    if (!latestGossip.hasMember(from))
      logInfo("Ignoring received gossip status from unknown [{}]", from)
    else if (!latestGossip.isReachable(selfUniqueAddress, from))
      logInfo("Ignoring received gossip status from unreachable [{}] ", from)
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
      logDebug("Ignoring received gossip from [{}] to protect against overload", from)
      Ignored
    } else if (envelope.to != selfUniqueAddress) {
      logInfo("Ignoring received gossip intended for someone else, from [{}] to [{}]", from.address, envelope.to)
      Ignored
    } else if (!localGossip.hasMember(from)) {
      logInfo("Ignoring received gossip from unknown [{}]", from)
      Ignored
    } else if (!localGossip.isReachable(selfUniqueAddress, from)) {
      logInfo("Ignoring received gossip from unreachable [{}] ", from)
      Ignored
    } else if (remoteGossip.members.forall(_.uniqueAddress != selfUniqueAddress)) {
      logInfo("Ignoring received gossip that does not contain myself, from [{}]", from)
      Ignored
    } else {
      val comparison = remoteGossip.version compareTo localGossip.version

      val (winningGossip, talkback, gossipType) = comparison match {
        case VectorClock.Same ⇒
          // same version
          val talkback = !exitingTasksInProgress && !remoteGossip.seenByNode(selfUniqueAddress)
          (remoteGossip mergeSeen localGossip, talkback, Same)
        case VectorClock.Before ⇒
          // local is newer
          (localGossip, true, Older)
        case VectorClock.After ⇒
          // remote is newer
          val talkback = !exitingTasksInProgress && !remoteGossip.seenByNode(selfUniqueAddress)
          (remoteGossip, talkback, Newer)
        case _ ⇒
          // conflicting versions, merge
          // We can see that a removal was done when it is not in one of the gossips has status
          // Down or Exiting in the other gossip.
          // Perform the same pruning (clear of VectorClock) as the leader did when removing a member.
          // Removal of member itself is handled in merge (pickHighestPriority)
          val prunedLocalGossip = localGossip.members.foldLeft(localGossip) { (g, m) ⇒
            if (removeUnreachableWithMemberStatus(m.status) && !remoteGossip.members.contains(m)) {
              logDebug("Pruned conflicting local gossip: {}", m)
              g.prune(VectorClock.Node(Gossip.vclockName(m.uniqueAddress)))
            } else
              g
          }
          val prunedRemoteGossip = remoteGossip.members.foldLeft(remoteGossip) { (g, m) ⇒
            if (removeUnreachableWithMemberStatus(m.status) && !localGossip.members.contains(m)) {
              logDebug("Pruned conflicting remote gossip: {}", m)
              g.prune(VectorClock.Node(Gossip.vclockName(m.uniqueAddress)))
            } else
              g
          }

          (prunedRemoteGossip merge prunedLocalGossip, true, Merge)
      }

      // Don't mark gossip state as seen while exiting is in progress, e.g.
      // shutting down singleton actors. This delays removal of the member until
      // the exiting tasks have been completed.
      membershipState = membershipState.copy(latestGossip =
        if (exitingTasksInProgress) winningGossip
        else winningGossip seen selfUniqueAddress)
      assertLatestGossip()

      // for all new nodes we remove them from the failure detector
      latestGossip.members foreach {
        node ⇒
          if (!localGossip.members(node)) {
            failureDetector.remove(node.address)
            crossDcFailureDetector.remove(node.address)
          }
      }

      logDebug("Receiving gossip from [{}]", from)

      if (comparison == VectorClock.Concurrent && cluster.settings.Debug.VerboseGossipLogging) {
        logDebug(
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

      publishMembershipState()

      val selfStatus = latestGossip.member(selfUniqueAddress).status
      if (selfStatus == Exiting && !exitingTasksInProgress) {
        // ExitingCompleted will be received via CoordinatedShutdown to continue
        // the leaving process. Meanwhile the gossip state is not marked as seen.
        exitingTasksInProgress = true
        if (coordShutdown.shutdownReason().isEmpty)
          logInfo("Exiting, starting coordinated shutdown")
        selfExiting.trySuccess(Done)
        coordShutdown.run(CoordinatedShutdown.ClusterLeavingReason)
      }

      if (talkback) {
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

  def isGossipSpeedupNeeded: Boolean = {
    if (latestGossip.isMultiDc)
      latestGossip.overview.seen.count(membershipState.isInSameDc) < latestGossip.members.count(_.dataCenter == cluster.selfDataCenter) / 2
    else
      latestGossip.overview.seen.size < latestGossip.members.size / 2
  }

  /**
   * Sends full gossip to `n` other random members.
   */
  def gossipRandomN(n: Int): Unit = {
    if (!isSingletonCluster && n > 0) {
      gossipTargetSelector.randomNodesForFullGossip(membershipState, n).foreach(gossipTo)
    }
  }

  /**
   * Initiates a new round of gossip.
   */
  def gossip(): Unit =
    if (!isSingletonCluster) {
      gossipTargetSelector.gossipTarget(membershipState) match {
        case Some(peer) ⇒
          if (!membershipState.isInSameDc(peer) || latestGossip.seenByNode(peer))
            // avoid transferring the full state if possible
            gossipStatusTo(peer)
          else
            gossipTo(peer)
        case None ⇒ // nothing to see here
          if (cluster.settings.Debug.VerboseGossipLogging)
            logDebug("will not gossip this round")

      }
    }

  /**
   * Runs periodic leader actions, such as member status transitions, assigning partitions etc.
   */
  def leaderActions(): Unit = {
    if (membershipState.isLeader(selfUniqueAddress)) {
      // only run the leader actions if we are the LEADER of the data center
      if (!isCurrentlyLeader) {
        logInfo("is the new leader among reachable nodes (more leaders may exist)")
        isCurrentlyLeader = true
      }
      val firstNotice = 20
      val periodicNotice = 60
      if (membershipState.convergence(exitingConfirmed)) {
        if (leaderActionCounter >= firstNotice)
          logInfo("Leader can perform its duties again")
        leaderActionCounter = 0
        leaderActionsOnConvergence()
      } else {
        leaderActionCounter += 1
        if (cluster.settings.AllowWeaklyUpMembers && leaderActionCounter >= 3)
          moveJoiningToWeaklyUp()

        if (leaderActionCounter == firstNotice || leaderActionCounter % periodicNotice == 0)
          logInfo(
            "Leader can currently not perform its duties, reachability status: [{}], member status: [{}]",
            membershipState.dcReachabilityExcludingDownedObservers,
            latestGossip.members.collect {
              case m if m.dataCenter == selfDc ⇒
                s"${m.address} ${m.status} seen=${latestGossip.seenByNode(m.uniqueAddress)}"
            }.mkString(", "))
      }
    } else if (isCurrentlyLeader) {
      logInfo("is no longer leader")
      isCurrentlyLeader = false
    }
    cleanupExitingConfirmed()
    shutdownSelfWhenDown()
  }

  def shutdownSelfWhenDown(): Unit = {
    if (latestGossip.member(selfUniqueAddress).status == Down) {
      // When all reachable have seen the state this member will shutdown itself when it has
      // status Down. The down commands should spread before we shutdown.
      val unreachable = membershipState.dcReachability.allUnreachableOrTerminated
      val downed = membershipState.dcMembers.collect { case m if m.status == Down ⇒ m.uniqueAddress }
      if (selfDownCounter >= MaxTicksBeforeShuttingDownMyself || downed.forall(node ⇒ unreachable(node) || latestGossip.seenByNode(node))) {
        // the reason for not shutting down immediately is to give the gossip a chance to spread
        // the downing information to other downed nodes, so that they can shutdown themselves
        logInfo("Shutting down myself")
        // not crucial to send gossip, but may speedup removal since fallback to failure detection is not needed
        // if other downed know that this node has seen the version
        gossipRandomN(MaxGossipsBeforeShuttingDownMyself)
        shutdown()
      } else {
        selfDownCounter += 1
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

    val removedUnreachable = for {
      node ← membershipState.dcReachability.allUnreachableOrTerminated
      m = latestGossip.member(node)
      if m.dataCenter == selfDc && removeUnreachableWithMemberStatus(m.status)
    } yield m

    val removedExitingConfirmed = exitingConfirmed.filter { n ⇒
      val member = latestGossip.member(n)
      member.dataCenter == selfDc && member.status == Exiting
    }

    val removedOtherDc =
      if (latestGossip.isMultiDc) {
        latestGossip.members.filter { m ⇒
          (m.dataCenter != selfDc && removeUnreachableWithMemberStatus(m.status))
        }
      } else
        Set.empty[Member]

    val changedMembers = {
      val enoughMembers: Boolean = isMinNrOfMembersFulfilled
      def isJoiningToUp(m: Member): Boolean = (m.status == Joining || m.status == WeaklyUp) && enoughMembers

      latestGossip.members collect {
        var upNumber = 0

        {
          case m if m.dataCenter == selfDc && isJoiningToUp(m) ⇒
            // Move JOINING => UP (once all nodes have seen that this node is JOINING, i.e. we have a convergence)
            // and minimum number of nodes have joined the cluster
            if (upNumber == 0) {
              // It is alright to use same upNumber as already used by a removed member, since the upNumber
              // is only used for comparing age of current cluster members (Member.isOlderThan)
              val youngest = membershipState.youngestMember
              upNumber = 1 + (if (youngest.upNumber == Int.MaxValue) 0 else youngest.upNumber)
            } else {
              upNumber += 1
            }
            m.copyUp(upNumber)

          case m if m.dataCenter == selfDc && m.status == Leaving ⇒
            // Move LEAVING => EXITING (once we have a convergence on LEAVING)
            m copy (status = Exiting)
        }
      }
    }

    val updatedGossip: Gossip =
      if (removedUnreachable.nonEmpty || removedExitingConfirmed.nonEmpty || changedMembers.nonEmpty ||
        removedOtherDc.nonEmpty) {

        // replace changed members
        val removed = removedUnreachable.map(_.uniqueAddress).union(removedExitingConfirmed)
          .union(removedOtherDc.map(_.uniqueAddress))
        val newGossip =
          latestGossip.update(changedMembers).removeAll(removed, System.currentTimeMillis())

        if (!exitingTasksInProgress && newGossip.member(selfUniqueAddress).status == Exiting) {
          // Leader is moving itself from Leaving to Exiting.
          // ExitingCompleted will be received via CoordinatedShutdown to continue
          // the leaving process. Meanwhile the gossip state is not marked as seen.
          exitingTasksInProgress = true
          if (coordShutdown.shutdownReason().isEmpty)
            logInfo("Exiting (leader), starting coordinated shutdown")
          selfExiting.trySuccess(Done)
          coordShutdown.run(CoordinatedShutdown.ClusterLeavingReason)
        }

        exitingConfirmed = exitingConfirmed.filterNot(removedExitingConfirmed)

        changedMembers foreach { m ⇒
          logInfo("Leader is moving node [{}] to [{}]", m.address, m.status)
        }
        removedUnreachable foreach { m ⇒
          val status = if (m.status == Exiting) "exiting" else "unreachable"
          logInfo("Leader is removing {} node [{}]", status, m.address)
        }
        removedExitingConfirmed.foreach { n ⇒
          logInfo("Leader is removing confirmed Exiting node [{}]", n.address)
        }
        removedOtherDc foreach { m ⇒
          logInfo("Leader is removing {} node [{}] in DC [{}]", m.status, m.address, m.dataCenter)
        }

        newGossip
      } else
        latestGossip

    val pruned = updatedGossip.pruneTombstones(System.currentTimeMillis() - PruneGossipTombstonesAfter.toMillis)
    if (pruned ne latestGossip) {
      updateLatestGossip(pruned)
      publishMembershipState()
      gossipExitingMembersToOldest(changedMembers.filter(_.status == Exiting))
    }
  }

  /**
   * Gossip the Exiting change to the two oldest nodes for quick dissemination to potential Singleton nodes
   */
  private def gossipExitingMembersToOldest(exitingMembers: Set[Member]): Unit = {
    val targets = membershipState.gossipTargetsForExitingMembers(exitingMembers)
    if (targets.nonEmpty) {

      if (isDebugEnabled)
        logDebug(
          "Gossip exiting members [{}] to the two oldest (per role) [{}] (singleton optimization).",
          exitingMembers.mkString(", "), targets.mkString(", "))

      targets.foreach(m ⇒ gossipTo(m.uniqueAddress))
    }
  }

  def moveJoiningToWeaklyUp(): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members

    val enoughMembers: Boolean = isMinNrOfMembersFulfilled
    def isJoiningToWeaklyUp(m: Member): Boolean =
      m.dataCenter == selfDc &&
        m.status == Joining &&
        enoughMembers &&
        membershipState.dcReachabilityExcludingDownedObservers.isReachable(m.uniqueAddress)
    val changedMembers = localMembers.collect {
      case m if isJoiningToWeaklyUp(m) ⇒ m.copy(status = WeaklyUp)
    }

    if (changedMembers.nonEmpty) {
      val newGossip = localGossip.update(changedMembers)
      updateLatestGossip(newGossip)

      // log status changes
      changedMembers foreach { m ⇒
        logInfo("Leader is moving node [{}] to [{}]", m.address, m.status)
      }

      publishMembershipState()
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

      def isAvailable(member: Member): Boolean = {
        if (member.dataCenter == SelfDataCenter) failureDetector.isAvailable(member.address)
        else crossDcFailureDetector.isAvailable(member.address)
      }

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒
        member.uniqueAddress == selfUniqueAddress ||
          localOverview.reachability.status(selfUniqueAddress, member.uniqueAddress) == Reachability.Unreachable ||
          localOverview.reachability.status(selfUniqueAddress, member.uniqueAddress) == Reachability.Terminated ||
          isAvailable(member)
      }

      val newlyDetectedReachableMembers = localOverview.reachability.allUnreachableFrom(selfUniqueAddress) collect {
        case node if node != selfUniqueAddress && isAvailable(localGossip.member(node)) ⇒
          localGossip.member(node)
      }

      if (newlyDetectedUnreachableMembers.nonEmpty || newlyDetectedReachableMembers.nonEmpty) {

        val newReachability1 = newlyDetectedUnreachableMembers.foldLeft(localOverview.reachability) {
          (reachability, m) ⇒ reachability.unreachable(selfUniqueAddress, m.uniqueAddress)
        }
        val newReachability2 = newlyDetectedReachableMembers.foldLeft(newReachability1) {
          (reachability, m) ⇒ reachability.reachable(selfUniqueAddress, m.uniqueAddress)
        }

        if (newReachability2 ne localOverview.reachability) {
          val newOverview = localOverview copy (reachability = newReachability2)
          val newGossip = localGossip copy (overview = newOverview)

          updateLatestGossip(newGossip)

          val (exiting, nonExiting) = newlyDetectedUnreachableMembers.partition(_.status == Exiting)
          if (nonExiting.nonEmpty)
            logWarning("Marking node(s) as UNREACHABLE [{}]. Node roles [{}]", nonExiting.mkString(", "), selfRoles.mkString(", "))
          if (exiting.nonEmpty)
            logInfo(
              "Marking exiting node(s) as UNREACHABLE [{}]. This is expected and they will be removed.",
              exiting.mkString(", "))
          if (newlyDetectedReachableMembers.nonEmpty)
            logInfo("Marking node(s) as REACHABLE [{}]. Node roles [{}]", newlyDetectedReachableMembers.mkString(", "), selfRoles.mkString(","))

          publishMembershipState()
        }
      }
    }
  }

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
    if (membershipState.validNodeForGossip(node))
      clusterCore(node.address) ! GossipEnvelope(selfUniqueAddress, node, latestGossip)

  def gossipTo(node: UniqueAddress, destination: ActorRef): Unit =
    if (membershipState.validNodeForGossip(node))
      destination ! GossipEnvelope(selfUniqueAddress, node, latestGossip)

  def gossipStatusTo(node: UniqueAddress, destination: ActorRef): Unit =
    if (membershipState.validNodeForGossip(node))
      destination ! GossipStatus(selfUniqueAddress, latestGossip.version)

  def gossipStatusTo(node: UniqueAddress): Unit =
    if (membershipState.validNodeForGossip(node))
      clusterCore(node.address) ! GossipStatus(selfUniqueAddress, latestGossip.version)

  def updateLatestGossip(gossip: Gossip): Unit = {
    // Updating the vclock version for the changes
    val versionedGossip = gossip :+ vclockNode

    // Don't mark gossip state as seen while exiting is in progress, e.g.
    // shutting down singleton actors. This delays removal of the member until
    // the exiting tasks have been completed.
    val newGossip =
      if (exitingTasksInProgress)
        versionedGossip.clearSeen()
      else {
        // Nobody else has seen this gossip but us
        val seenVersionedGossip = versionedGossip onlySeen selfUniqueAddress
        // Update the state with the new gossip
        seenVersionedGossip
      }
    membershipState = membershipState.copy(newGossip)
    assertLatestGossip()
  }

  def assertLatestGossip(): Unit =
    if (Cluster.isAssertInvariantsEnabled && latestGossip.version.versions.size > latestGossip.members.size)
      throw new IllegalStateException(s"Too many vector clock entries in gossip state $latestGossip")

  def publishMembershipState(): Unit = {
    if (cluster.settings.Debug.VerboseGossipLogging)
      logDebug("New gossip published [{}]", membershipState.latestGossip)

    publisher ! PublishChanges(membershipState)
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
 */
private[cluster] case object IncompatibleConfigurationDetected extends Reason

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
@InternalApi
private[cluster] final class FirstSeedNodeProcess(seedNodes: immutable.IndexedSeq[Address], joinConfigCompatChecker: JoinConfigCompatChecker) extends Actor {
  import InternalClusterAction._
  import ClusterUserAction.JoinTo

  val cluster = Cluster(context.system)
  import cluster.settings._
  import cluster.ClusterLogger._

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
        val requiredNonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joinConfigCompatChecker.requiredKeys, cluster.settings)
        // configToValidate only contains the keys that are required according to JoinConfigCompatChecker on this node
        val configToValidate = JoinConfigCompatChecker.filterWithKeys(requiredNonSensitiveKeys, context.system.settings.config)
        // send InitJoin to remaining seed nodes (except myself)
        remainingSeedNodes foreach { a ⇒ context.actorSelection(context.parent.path.toStringWithAddress(a)) ! InitJoin(configToValidate) }
      } else {
        // no InitJoinAck received, initialize new cluster by joining myself
        if (isDebugEnabled)
          logDebug(
            "Couldn't join other seed nodes, will join myself. seed-nodes=[{}]",
            seedNodes.mkString(", "))
        context.parent ! JoinTo(selfAddress)
        context.stop(self)
      }

    case InitJoinAck(address, CompatibleConfig(clusterConfig)) ⇒
      logInfo("Received InitJoinAck message from [{}] to [{}]", sender(), selfAddress)
      // validates config coming from cluster against this node config
      joinConfigCompatChecker.check(clusterConfig, context.system.settings.config) match {
        case Valid ⇒
          // first InitJoinAck reply
          context.parent ! JoinTo(address)
          context.stop(self)

        case Invalid(messages) if ByPassConfigCompatCheck ⇒
          logWarning("Cluster validated this node config, but sent back incompatible settings: {}. " +
            "Join will be performed because compatibility check is configured to not be enforced.", messages.mkString(", "))
          context.parent ! JoinTo(address)
          context.stop(self)

        case Invalid(messages) ⇒
          logError("Cluster validated this node config, but sent back incompatible settings: {}. " +
            "It's recommended to perform a full cluster shutdown in order to deploy this new version. " +
            "If a cluster shutdown isn't an option, you may want to disable this protection by setting " +
            "'akka.cluster.configuration-compatibility-check.enforce-on-join = off'. " +
            "Note that disabling it will allow the formation of a cluster with nodes having incompatible configuration settings. " +
            "This node will be shutdown!", messages.mkString(", "))
          context.stop(self)
          CoordinatedShutdown(context.system).run(IncompatibleConfigurationDetected)
      }

    case InitJoinAck(address, UncheckedConfig) ⇒
      logInfo("Received InitJoinAck message from [{}] to [{}]", sender(), selfAddress)
      logWarning("Joining a cluster without configuration compatibility check feature.")
      context.parent ! JoinTo(address)
      context.stop(self)

    case InitJoinAck(address, IncompatibleConfig) ⇒
      // first InitJoinAck reply, but incompatible
      if (ByPassConfigCompatCheck) {
        // only join if set to ignore config validation
        logInfo("Received InitJoinAck message from [{}] to [{}]", sender(), selfAddress)
        logWarning("Joining cluster with incompatible configurations. " +
          "Join will be performed because compatibility check is configured to not be enforced.")
        context.parent ! JoinTo(address)
        context.stop(self)
      } else {
        logError(
          "Couldn't join seed nodes because of incompatible cluster configuration. " +
            "It's recommended to perform a full cluster shutdown in order to deploy this new version." +
            "If a cluster shutdown isn't an option, you may want to disable this protection by setting " +
            "'akka.cluster.configuration-compatibility-check.enforce-on-join = off'. " +
            "Note that disabling it will allow the formation of a cluster with nodes having incompatible configuration settings. " +
            "This node will be shutdown!")
        context.stop(self)
        CoordinatedShutdown(context.system).run(IncompatibleConfigurationDetected)
      }

    case InitJoinNack(address) ⇒
      logInfo("Received InitJoinNack message from [{}] to [{}]", sender(), selfAddress)
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
@InternalApi
private[cluster] final class JoinSeedNodeProcess(seedNodes: immutable.IndexedSeq[Address], joinConfigCompatChecker: JoinConfigCompatChecker) extends Actor {
  import InternalClusterAction._
  import ClusterUserAction.JoinTo

  val cluster = Cluster(context.system)
  import cluster.settings._
  import cluster.ClusterLogger._
  def selfAddress = cluster.selfAddress

  if (seedNodes.isEmpty || seedNodes.head == selfAddress)
    throw new IllegalArgumentException("Join seed node should not be done")

  context.setReceiveTimeout(SeedNodeTimeout)

  var attempt = 0

  // all seed nodes, except this one
  val otherSeedNodes = seedNodes.toSet - selfAddress

  override def preStart(): Unit = self ! JoinSeedNode

  def receive = {
    case JoinSeedNode ⇒
      val requiredNonSensitiveKeys = JoinConfigCompatChecker.removeSensitiveKeys(joinConfigCompatChecker.requiredKeys, cluster.settings)
      // configToValidate only contains the keys that are required according to JoinConfigCompatChecker on this node
      val configToValidate = JoinConfigCompatChecker.filterWithKeys(requiredNonSensitiveKeys, context.system.settings.config)
      // send InitJoin to all seed nodes (except myself)
      attempt += 1
      otherSeedNodes.foreach { a ⇒ context.actorSelection(context.parent.path.toStringWithAddress(a)) ! InitJoin(configToValidate) }

    case InitJoinAck(address, CompatibleConfig(clusterConfig)) ⇒
      logInfo("Received InitJoinAck message from [{}] to [{}]", sender(), selfAddress)
      // validates config coming from cluster against this node config
      joinConfigCompatChecker.check(clusterConfig, context.system.settings.config) match {
        case Valid ⇒
          // first InitJoinAck reply
          context.parent ! JoinTo(address)
          context.become(done)

        case Invalid(messages) if ByPassConfigCompatCheck ⇒
          logWarning("Cluster validated this node config, but sent back incompatible settings: {}. " +
            "Join will be performed because compatibility check is configured to not be enforced.", messages.mkString(", "))
          context.parent ! JoinTo(address)
          context.become(done)

        case Invalid(messages) ⇒
          logError("Cluster validated this node config, but sent back incompatible settings: {}. " +
            "It's recommended to perform a full cluster shutdown in order to deploy this new version. " +
            "If a cluster shutdown isn't an option, you may want to disable this protection by setting " +
            "'akka.cluster.configuration-compatibility-check.enforce-on-join = off'. " +
            "Note that disabling it will allow the formation of a cluster with nodes having incompatible configuration settings. " +
            "This node will be shutdown!", messages.mkString(", "))
          context.stop(self)
          CoordinatedShutdown(context.system).run(IncompatibleConfigurationDetected)
      }

    case InitJoinAck(address, UncheckedConfig) ⇒
      logWarning("Joining a cluster without configuration compatibility check feature.")
      context.parent ! JoinTo(address)
      context.become(done)

    case InitJoinAck(address, IncompatibleConfig) ⇒
      // first InitJoinAck reply, but incompatible
      if (ByPassConfigCompatCheck) {
        logInfo("Received InitJoinAck message from [{}] to [{}]", sender(), selfAddress)
        logWarning("Joining cluster with incompatible configurations. " +
          "Join will be performed because compatibility check is configured to not be enforced.")
        // only join if set to ignore config validation
        context.parent ! JoinTo(address)
        context.become(done)
      } else {
        logError(
          "Couldn't join seed nodes because of incompatible cluster configuration. " +
            "It's recommended to perform a full cluster shutdown in order to deploy this new version." +
            "If a cluster shutdown isn't an option, you may want to disable this protection by setting " +
            "'akka.cluster.configuration-compatibility-check.enforce-on-join = off'. " +
            "Note that disabling it will allow the formation of a cluster with nodes having incompatible configuration settings. " +
            "This node will be shutdown!")
        context.stop(self)
        CoordinatedShutdown(context.system).run(IncompatibleConfigurationDetected)
      }

    case InitJoinNack(_) ⇒ // that seed was uninitialized

    case ReceiveTimeout ⇒
      if (attempt >= 2)
        logWarning(
          "Couldn't join seed nodes after [{}] attempts, will try again. seed-nodes=[{}]",
          attempt, seedNodes.filterNot(_ == selfAddress).mkString(", "))
      // no InitJoinAck received, try again
      self ! JoinSeedNode
  }

  def done: Actor.Receive = {
    case InitJoinAck(_, _) ⇒ // already received one, skip rest
    case ReceiveTimeout    ⇒ context.stop(self)
  }
}

/**
 * INTERNAL API
 *
 * The supplied callback will be run, once, when current cluster member come up with the same status.
 */
@InternalApi
private[cluster] class OnMemberStatusChangedListener(callback: Runnable, status: MemberStatus) extends Actor {
  import ClusterEvent._
  private val cluster = Cluster(context.system)
  import cluster.ClusterLogger._

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
      case NonFatal(e) ⇒ logError(e, "[{}] callback failed with [{}]", s"On${to.getSimpleName}", e.getMessage)
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
@InternalApi
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
@InternalApi
@SerialVersionUID(1L)
private[cluster] final case class VectorClockStats(
  versionSize: Int = 0,
  seenLatest:  Int = 0)
