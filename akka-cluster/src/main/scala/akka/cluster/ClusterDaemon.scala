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
import akka.actor.Status.Failure
import akka.actor.SupervisorStrategy.Stop
import akka.actor.Terminated
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._

/**
 * Base trait for all cluster messages. All ClusterMessage's are serializable.
 *
 * FIXME Protobuf all ClusterMessages
 */
trait ClusterMessage extends Serializable

/**
 * Cluster commands sent by the USER.
 */
object ClusterUserAction {

  /**
   * Command to join the cluster. Sent when a node (represented by 'address')
   * wants to join another node (the receiver).
   */
  case class Join(address: Address) extends ClusterMessage

  /**
   * Command to leave the cluster.
   */
  case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  case class Down(address: Address) extends ClusterMessage

}

/**
 * INTERNAL API
 */
private[cluster] object InternalClusterAction {

  /**
   * Command to initiate join another node (represented by 'address').
   * Join will be sent to the other node.
   */
  case class JoinTo(address: Address) extends ClusterMessage

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
  case object JoinSeedNode extends ClusterMessage

  /**
   * @see JoinSeedNode
   */
  case object InitJoin extends ClusterMessage

  /**
   * @see JoinSeedNode
   */
  case class InitJoinAck(address: Address) extends ClusterMessage

  /**
   * @see JoinSeedNode
   */
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
  case object PublishStart extends PublishMessage
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
   */
  case class Exit(address: Address) extends ClusterMessage

  /**
   * Command to remove a node from the cluster immediately.
   */
  case class Remove(address: Address) extends ClusterMessage
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
      context.actorOf(Props(new OnMemberUpListener(code)))
    case PublisherCreated(publisher) ⇒
      if (settings.MetricsEnabled) {
        // metrics must be started after core/publisher to be able
        // to inject the publisher ref to the ClusterMetricsCollector
        context.actorOf(Props(new ClusterMetricsCollector(publisher)).
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
  val coreDaemon = context.watch(context.actorOf(Props(new ClusterCoreDaemon(publisher)).
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
  import cluster.{ selfAddress, scheduler, failureDetector }
  import cluster.settings._

  // FIXME the UUID should not be needed when Address contains uid, ticket #2788
  val vclockNode = VectorClock.Node(selfAddress.toString + "-" + UUID.randomUUID())

  // note that self is not initially member,
  // and the Gossip is not versioned for this 'Node' yet
  var latestGossip: Gossip = Gossip.empty

  var stats = ClusterStats()

  var seedNodeProcess: Option[ActorRef] = None

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCore(address: Address): ActorRef =
    context.actorFor(RootActorPath(address) / "system" / "cluster" / "core" / "daemon")

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
  val publishStatsTask: Option[Cancellable] =
    if (PublishStatsInterval == Duration.Zero) None
    else Some(scheduler.schedule(PeriodicTasksInitialDelay.max(PublishStatsInterval),
      PublishStatsInterval, self, PublishStatsTick))

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
    case InitJoin                 ⇒ sender ! InitJoinNack(selfAddress)
    case JoinTo(address)          ⇒ join(address)
    case JoinSeedNodes(seedNodes) ⇒ joinSeedNodes(seedNodes)
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick                  ⇒ // ignore periodic tasks until initialized
  }

  def initialized: Actor.Receive = {
    case msg: GossipEnvelope              ⇒ receiveGossip(msg)
    case msg: GossipMergeConflict         ⇒ receiveGossipMerge(msg)
    case GossipTick                       ⇒ gossip()
    case ReapUnreachableTick              ⇒ reapUnreachableMembers()
    case LeaderActionsTick                ⇒ leaderActions()
    case PublishStatsTick                 ⇒ publishInternalStats()
    case InitJoin                         ⇒ initJoin()
    case JoinTo(address)                  ⇒ join(address)
    case ClusterUserAction.Join(address)  ⇒ joining(address)
    case ClusterUserAction.Down(address)  ⇒ downing(address)
    case ClusterUserAction.Leave(address) ⇒ leaving(address)
    case Exit(address)                    ⇒ exiting(address)
    case Remove(address)                  ⇒ removing(address)
    case SendGossipTo(address)            ⇒ gossipTo(address)
    case msg: SubscriptionMessage         ⇒ publisher forward msg

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
        self ! JoinTo(selfAddress)
        None
      } else if (seedNodes.head == selfAddress) {
        Some(context.actorOf(Props(new FirstSeedNodeProcess(seedNodes)).
          withDispatcher(UseDispatcher), name = "firstSeedNodeProcess"))
      } else {
        Some(context.actorOf(Props(new JoinSeedNodeProcess(seedNodes)).
          withDispatcher(UseDispatcher), name = "joinSeedNodeProcess"))
      }
  }

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: Address): Unit = {
    if (address.protocol != selfAddress.protocol)
      log.warning("Trying to join member with wrong protocol, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, address.protocol)
    else if (address.system != selfAddress.system)
      log.warning("Trying to join member with wrong ActorSystem name, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, address.system)
    else if (!latestGossip.members.exists(_.address == address)) {

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

      // wipe our state since a node that joins a cluster must be empty
      latestGossip = Gossip.empty
      // wipe the failure detector since we are starting fresh and shouldn't care about the past
      failureDetector.reset()
      // wipe the publisher since we are starting fresh
      publisher ! PublishStart

      publish(latestGossip)

      context.become(initialized)
      if (address == selfAddress)
        joining(address)
      else
        clusterCore(address) ! ClusterUserAction.Join(selfAddress)
    }
  }

  /**
   * State transition to JOINING - new node joining.
   */
  def joining(node: Address): Unit = {
    if (node.protocol != selfAddress.protocol)
      log.warning("Member with wrong protocol tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.protocol, node.protocol)
    else if (node.system != selfAddress.system)
      log.warning("Member with wrong ActorSystem name tried to join, but was ignored, expected [{}] but was [{}]",
        selfAddress.system, node.system)
    else {
      val localMembers = latestGossip.members
      val localUnreachable = latestGossip.overview.unreachable

      val alreadyMember = localMembers.exists(_.address == node)
      val isUnreachable = localUnreachable.exists(_.address == node)

      if (!alreadyMember && !isUnreachable) {

        // remove the node from the failure detector
        failureDetector.remove(node)

        // add joining node as Joining
        // add self in case someone else joins before self has joined (Set discards duplicates)
        val newMembers = localMembers + Member(node, Joining) + Member(selfAddress, Joining)
        val newGossip = latestGossip copy (members = newMembers)

        val versionedGossip = newGossip :+ vclockNode
        val seenVersionedGossip = versionedGossip seen selfAddress

        latestGossip = seenVersionedGossip

        log.debug("Cluster Node [{}] - Node [{}] is JOINING", selfAddress, node)
        // treat join as initial heartbeat, so that it becomes unavailable if nothing more happens
        if (node != selfAddress) {
          gossipTo(node)
        }

        publish(latestGossip)
      }
    }
  }

  /**
   * State transition to LEAVING.
   */
  def leaving(address: Address): Unit = {
    if (latestGossip.members.exists(_.address == address)) { // only try to update if the node is available (in the member ring)
      val newMembers = latestGossip.members map { member ⇒ if (member.address == address) Member(address, Leaving) else member } // mark node as LEAVING
      val newGossip = latestGossip copy (members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      latestGossip = seenVersionedGossip

      log.info("Cluster Node [{}] - Marked address [{}] as LEAVING", selfAddress, address)
      publish(latestGossip)
    }
  }

  /**
   * State transition to EXITING.
   */
  def exiting(address: Address): Unit = {
    log.info("Cluster Node [{}] - Marked node [{}] as EXITING", selfAddress, address)
    // FIXME implement when we implement hand-off
  }

  /**
   * State transition to REMOVED.
   *
   * This method is for now only called after the LEADER have sent a Removed message - telling the node
   * to shut down himself.
   *
   * In the future we might change this to allow the USER to send a Removed(address) message telling an
   * arbitrary node to be moved direcly from UP -> REMOVED.
   */
  def removing(address: Address): Unit = {
    log.info("Cluster Node [{}] - Node has been REMOVED by the leader - shutting down...", selfAddress)
    cluster.shutdown()
  }

  /**
   * The node to DOWN is removed from the 'members' set and put in the 'unreachable' set (if not already there)
   * and its status is set to DOWN. The node is also removed from the 'seen' table.
   *
   * The node will reside as DOWN in the 'unreachable' set until an explicit command JOIN command is sent directly
   * to this node and it will then go through the normal JOINING procedure.
   */
  def downing(address: Address): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen
    val localUnreachableMembers = localOverview.unreachable

    // 1. check if the node to DOWN is in the 'members' set
    val downedMember: Option[Member] =
      localMembers.collectFirst { case m if m.address == address ⇒ m.copy(status = Down) }

    val newMembers = downedMember match {
      case Some(m) ⇒
        log.info("Cluster Node [{}] - Marking node [{}] as DOWN", selfAddress, m.address)
        localMembers - m
      case None ⇒ localMembers
    }

    // 2. check if the node to DOWN is in the 'unreachable' set
    val newUnreachableMembers =
      localUnreachableMembers.map { member ⇒
        // no need to DOWN members already DOWN
        if (member.address == address && member.status != Down) {
          log.info("Cluster Node [{}] - Marking unreachable node [{}] as DOWN", selfAddress, member.address)
          member copy (status = Down)
        } else member
      }

    // 3. add the newly DOWNED members from the 'members' (in step 1.) to the 'newUnreachableMembers' set.
    val newUnreachablePlusNewlyDownedMembers = newUnreachableMembers ++ downedMember

    // 4. remove nodes marked as DOWN from the 'seen' table
    val newSeen = localSeen -- newUnreachablePlusNewlyDownedMembers.collect { case m if m.status == Down ⇒ m.address }

    // update gossip overview
    val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachablePlusNewlyDownedMembers)
    val newGossip = localGossip copy (overview = newOverview, members = newMembers) // update gossip
    val versionedGossip = newGossip :+ vclockNode
    latestGossip = versionedGossip seen selfAddress

    publish(latestGossip)
  }

  /**
   * When conflicting versions of received and local [[akka.cluster.Gossip]] is detected
   * it's forwarded to the leader for conflict resolution. Trying to simultaneously
   * resolving conflicts at several nodes creates new conflicts. Therefore the leader resolves
   * conflicts to limit divergence. To avoid overload there is also a configurable rate
   * limit of how many conflicts that are handled by second. If the limit is
   * exceeded the conflicting gossip messages are dropped and will reappear later.
   */
  def receiveGossipMerge(merge: GossipMergeConflict): Unit = {
    stats = stats.incrementMergeConflictCount
    val rate = mergeRate(stats.mergeConflictCount)
    if (rate <= MaxGossipMergeRate) {
      receiveGossip(merge.a.copy(conversation = false))
      receiveGossip(merge.b.copy(conversation = false))

      // use one-way gossip from leader to reduce load of leader
      def sendBack(to: Address): Unit = {
        if (to != selfAddress && !latestGossip.overview.unreachable.exists(_.address == to))
          oneWayGossipTo(to)
      }

      sendBack(merge.a.from)
      sendBack(merge.b.from)

    } else {
      log.debug("Dropping gossip merge conflict due to rate [{}] / s ", rate)
    }
  }

  /**
   * Receive new gossip.
   */
  def receiveGossip(envelope: GossipEnvelope): Unit = {
    val from = envelope.from
    val remoteGossip = envelope.gossip
    val localGossip = latestGossip

    if (remoteGossip.overview.unreachable.exists(_.address == selfAddress)) {
      log.debug("Ignoring received gossip with self [{}] as unreachable, from [{}]", selfAddress, from)
    } else if (localGossip.overview.isNonDownUnreachable(from)) {
      log.debug("Ignoring received gossip from unreachable [{}] ", from)
    } else {

      // leader handles merge conflicts, or when they have different views of how is leader
      val handleMerge = localGossip.leader == Some(selfAddress) || localGossip.leader != remoteGossip.leader
      val comparison = remoteGossip.version tryCompareTo localGossip.version
      val conflict = comparison.isEmpty

      if (conflict && !handleMerge) {
        // delegate merge resolution to leader to reduce number of simultaneous resolves,
        // which will result in new conflicts

        stats = stats.incrementMergeDetectedCount
        log.debug("Merge conflict [{}] detected [{}] <> [{}]", stats.mergeDetectedCount, selfAddress, from)

        stats = stats.incrementMergeConflictCount
        val rate = mergeRate(stats.mergeConflictCount)

        if (rate <= MaxGossipMergeRate)
          localGossip.leader foreach { clusterCore(_) ! GossipMergeConflict(GossipEnvelope(selfAddress, localGossip), envelope) }
        else
          log.debug("Skipping gossip merge conflict due to rate [{}] / s ", rate)

      } else {

        val (winningGossip, talkback, newStats) = comparison match {
          case None ⇒
            // conflicting versions, merge, and new version
            ((remoteGossip merge localGossip) :+ vclockNode, true, stats)
          case Some(0) ⇒
            // same version
            // TODO optimize talkback based on how the merged seen differs
            (remoteGossip mergeSeen localGossip, !remoteGossip.hasSeen(selfAddress), stats.incrementSameCount)
          case Some(x) if x < 0 ⇒
            // local is newer
            (localGossip, true, stats.incrementNewerCount)
          case _ ⇒
            // remote is newer
            (remoteGossip, !remoteGossip.hasSeen(selfAddress), stats.incrementOlderCount)
        }

        stats = newStats
        latestGossip = winningGossip seen selfAddress

        // for all new joining nodes we remove them from the failure detector
        (latestGossip.members -- localGossip.members).foreach {
          node ⇒ if (node.status == Joining) failureDetector.remove(node.address)
        }

        log.debug("Cluster Node [{}] - Receiving gossip from [{}]", selfAddress, from)

        if (conflict) {
          stats = stats.incrementMergeCount
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
  }

  def mergeRate(count: Long): Double = (count * 1000.0) / GossipInterval.toMillis

  /**
   * Initiates a new round of gossip.
   */
  def gossip(): Unit = {
    stats = stats.copy(mergeConflictCount = 0)

    log.debug("Cluster Node [{}] - Initiating new round of gossip", selfAddress)

    if (!isSingletonCluster && isAvailable) {
      val localGossip = latestGossip

      val preferredGossipTargets =
        if (ThreadLocalRandom.current.nextDouble() < GossipDifferentViewProbability) { // If it's time to try to gossip to some nodes with a different view
          // gossip to a random alive member with preference to a member with older or newer gossip version
          val localMemberAddressesSet = localGossip.members map { _.address }
          val nodesWithDifferentView = for {
            (address, version) ← localGossip.overview.seen
            if localMemberAddressesSet contains address
            if version != localGossip.version
          } yield address

          nodesWithDifferentView.toIndexedSeq
        } else Vector.empty[Address]

      gossipToRandomNodeOf(
        if (preferredGossipTargets.nonEmpty) preferredGossipTargets
        else localGossip.members.toIndexedSeq.map(_.address) // Fall back to localGossip; important to not accidentally use `map` of the SortedSet, since the original order is not preserved)
        )
    }
  }

  /**
   * Runs periodic leader actions, such as auto-downing unreachable nodes, assigning partitions etc.
   */
  def leaderActions(): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members

    val isLeader = localGossip.isLeader(selfAddress)

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
      //   8. Updating the 'seen' table
      //   9. Try to update the state with the new gossip
      //  10. If success - run all the side-effecting processing

      val (
        newGossip: Gossip,
        hasChangedState: Boolean,
        upMembers,
        exitingMembers,
        removedMembers,
        unreachableButNotDownedMembers) =

        if (localGossip.convergence) {
          // we have convergence - so we can't have unreachable nodes

          val numberOfMembers = localMembers.size
          def isJoiningToUp(m: Member): Boolean = m.status == Joining && numberOfMembers >= MinNrOfMembers

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
            // Move EXITING => REMOVED, DOWN => REMOVED - i.e. remove the nodes from the 'members' set/node ring and seen table
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
          val removedMembers2 = removedMembers ++ localUnreachableMembers.filter(_.status == Down)

          val (upMembers, newMembers2) = newMembers1 partition (isJoiningToUp(_))

          val exitingMembers = newMembers2 filter (_.status == Leaving && hasPartionHandoffCompletedSuccessfully)

          val hasChangedState = removedMembers2.nonEmpty || upMembers.nonEmpty || exitingMembers.nonEmpty

          // removing REMOVED nodes from the 'seen' table
          val newSeen = localSeen -- removedMembers2.map(_.address)

          // removing REMOVED nodes from the 'unreachable' set
          val newUnreachableMembers = localUnreachableMembers -- removedMembers2

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (members = newMembers, overview = newOverview) // update gossip

          (newGossip, hasChangedState, upMembers, exitingMembers, removedMembers2, Member.none)

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

          // removing nodes marked as DOWN from the 'seen' table
          val newSeen = localSeen -- newUnreachableMembers.collect { case m if m.status == Down ⇒ m.address }

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (overview = newOverview) // update gossip

          (newGossip, unreachableButNotDownedMembers.nonEmpty, Member.none, Member.none, Member.none, unreachableButNotDownedMembers)

        } else (localGossip, false, Member.none, Member.none, Member.none, Member.none)

      if (hasChangedState) { // we have a change of state - version it and try to update
        // ----------------------
        // Updating the vclock version for the changes
        // ----------------------
        val versionedGossip = newGossip :+ vclockNode

        // ----------------------
        // Updating the 'seen' table
        // Unless the leader (this node) is part of the removed members, i.e. the leader have moved himself from EXITING -> REMOVED
        // ----------------------
        val seenVersionedGossip =
          if (removedMembers.exists(_.address == selfAddress)) versionedGossip
          else versionedGossip seen selfAddress

        // ----------------------
        // Update the state with the new gossip
        // ----------------------
        latestGossip = seenVersionedGossip

        // ----------------------
        // Run all the side-effecting processing
        // ----------------------

        // log the move of members from joining to up
        upMembers foreach { member ⇒ log.info("Cluster Node [{}] - Leader is moving node [{}] from JOINING to UP", selfAddress, member.address) }

        //  tell all removed members to remove and shut down themselves
        removedMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from EXITING to REMOVED - and removing node from node ring", selfAddress, address)
          clusterCore(address) ! ClusterLeaderAction.Remove(address)
        }

        //  tell all exiting members to exit
        exitingMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from LEAVING to EXITING", selfAddress, address)
          clusterCore(address) ! ClusterLeaderAction.Exit(address) // FIXME should use ? to await completion of handoff?
        }

        // log the auto-downing of the unreachable nodes
        unreachableButNotDownedMembers foreach { member ⇒
          log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as DOWN", selfAddress, member.address)
        }

        publish(latestGossip)
      }
    }
  }

  /**
   * Reaps the unreachable members (moves them to the 'unreachable' list in the cluster overview) according to the failure detector's verdict.
   */
  def reapUnreachableMembers(): Unit = {
    if (!isSingletonCluster && isAvailable) {
      // only scrutinize if we are a non-singleton cluster and available

      val localGossip = latestGossip
      val localOverview = localGossip.overview
      val localMembers = localGossip.members
      val localUnreachableMembers = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒
        member.address == selfAddress || failureDetector.isAvailable(member.address)
      }

      if (newlyDetectedUnreachableMembers.nonEmpty) {

        val newMembers = localMembers -- newlyDetectedUnreachableMembers
        val newUnreachableMembers = localUnreachableMembers ++ newlyDetectedUnreachableMembers

        val newOverview = localOverview copy (unreachable = newUnreachableMembers)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        // updating vclock and 'seen' table
        val versionedGossip = newGossip :+ vclockNode
        val seenVersionedGossip = versionedGossip seen selfAddress

        latestGossip = seenVersionedGossip

        log.error("Cluster Node [{}] - Marking node(s) as UNREACHABLE [{}]", selfAddress, newlyDetectedUnreachableMembers.mkString(", "))

        publish(latestGossip)
      }
    }
  }

  def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None
    else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def isSingletonCluster: Boolean = latestGossip.isSingletonCluster

  def isAvailable: Boolean = !latestGossip.isUnreachable(selfAddress)

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return the used [[akka.actor.Address] if any
   */
  private def gossipToRandomNodeOf(addresses: immutable.IndexedSeq[Address]): Option[Address] = {
    log.debug("Cluster Node [{}] - Selecting random node to gossip to [{}]", selfAddress, addresses.mkString(", "))
    // filter out myself
    val peer = selectRandomNode(addresses filterNot (_ == selfAddress))
    peer foreach gossipTo
    peer
  }

  /**
   * Gossips latest gossip to an address.
   */
  def gossipTo(address: Address): Unit =
    gossipTo(address, GossipEnvelope(selfAddress, latestGossip, conversation = true))

  def oneWayGossipTo(address: Address): Unit =
    gossipTo(address, GossipEnvelope(selfAddress, latestGossip, conversation = false))

  def gossipTo(address: Address, gossipMsg: GossipEnvelope): Unit =
    if (address != selfAddress && gossipMsg.gossip.members.exists(_.address == address))
      clusterCore(address) ! gossipMsg

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
        remainingSeedNodes foreach { a ⇒ context.actorFor(context.parent.path.toStringWithAddress(a)) ! InitJoin }
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

  def selfAddress = Cluster(context.system).selfAddress

  if (seedNodes.isEmpty || seedNodes.head == selfAddress)
    throw new IllegalArgumentException("Join seed node should not be done")

  context.setReceiveTimeout(Cluster(context.system).settings.SeedNodeTimeout)

  override def preStart(): Unit = self ! JoinSeedNode

  def receive = {
    case JoinSeedNode ⇒
      // send InitJoin to all seed nodes (except myself)
      seedNodes.collect {
        case a if a != selfAddress ⇒ context.actorFor(context.parent.path.toStringWithAddress(a))
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
    m.address == cluster.selfAddress && m.status == MemberStatus.Up

}

/**
 * INTERNAL API
 */
private[cluster] case class ClusterStats(
  receivedGossipCount: Long = 0L,
  mergeConflictCount: Long = 0L,
  mergeCount: Long = 0L,
  mergeDetectedCount: Long = 0L,
  sameCount: Long = 0L,
  newerCount: Long = 0L,
  olderCount: Long = 0L) {

  def incrementReceivedGossipCount(): ClusterStats =
    copy(receivedGossipCount = receivedGossipCount + 1)

  def incrementMergeConflictCount(): ClusterStats =
    copy(mergeConflictCount = mergeConflictCount + 1)

  def incrementMergeCount(): ClusterStats =
    copy(mergeCount = mergeCount + 1)

  def incrementMergeDetectedCount(): ClusterStats =
    copy(mergeDetectedCount = mergeDetectedCount + 1)

  def incrementSameCount(): ClusterStats =
    copy(sameCount = sameCount + 1)

  def incrementNewerCount(): ClusterStats =
    copy(newerCount = newerCount + 1)

  def incrementOlderCount(): ClusterStats =
    copy(olderCount = olderCount + 1)

  def :+(that: ClusterStats): ClusterStats = {
    ClusterStats(
      this.receivedGossipCount + that.receivedGossipCount,
      this.mergeConflictCount + that.mergeConflictCount,
      this.mergeCount + that.mergeCount,
      this.mergeDetectedCount + that.mergeDetectedCount,
      this.sameCount + that.sameCount,
      this.newerCount + that.newerCount,
      this.olderCount + that.olderCount)
  }

  def :-(that: ClusterStats): ClusterStats = {
    ClusterStats(
      this.receivedGossipCount - that.receivedGossipCount,
      this.mergeConflictCount - that.mergeConflictCount,
      this.mergeCount - that.mergeCount,
      this.mergeDetectedCount - that.mergeDetectedCount,
      this.sameCount - that.sameCount,
      this.newerCount - that.newerCount,
      this.olderCount - that.olderCount)
  }

}
