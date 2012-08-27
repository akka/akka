/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import scala.concurrent.util.{ Deadline, Duration }
import scala.concurrent.util.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.{ Actor, ActorLogging, ActorRef, Address, Cancellable, Props, ReceiveTimeout, RootActorPath, PoisonPill, Scheduler }
import akka.actor.Status.Failure
import akka.event.EventStream
import akka.pattern.ask
import akka.util.Timeout
import akka.cluster.MemberStatus._
import akka.cluster.ClusterEvent._
import language.existentials
import language.postfixOps

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
   * Start message of the process to join one of the seed nodes.
   * The node sends `InitJoin` to all seed nodes, which replies
   * with `InitJoinAck`. The first reply is used others are discarded.
   * The node sends `Join` command to the seed node that replied first.
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
   * Marker interface for periodic tick messages
   */
  sealed trait Tick

  case object GossipTick extends Tick

  case object HeartbeatTick extends Tick

  case object ReapUnreachableTick extends Tick

  case object LeaderActionsTick extends Tick

  case object PublishStatsTick extends Tick

  case class SendClusterMessage(to: Address, msg: ClusterMessage)

  case class SendGossipTo(address: Address)

  case object GetClusterCoreRef

  sealed trait SubscriptionMessage
  case class Subscribe(subscriber: ActorRef, to: Class[_]) extends SubscriptionMessage
  case class Unsubscribe(subscriber: ActorRef) extends SubscriptionMessage

  case class PublishChanges(oldGossip: Gossip, newGossip: Gossip)
  case object PublishDone

  case class Ping(timestamp: Long = System.currentTimeMillis) extends ClusterMessage
  case class Pong(ping: Ping, timestamp: Long = System.currentTimeMillis) extends ClusterMessage

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
 * INTERNAL API
 *
 * The contextual pieces that ClusterDaemon actors need.
 * Makes it easier to test the actors without using the Cluster extension.
 */
private[cluster] trait ClusterEnvironment {
  private[cluster] def settings: ClusterSettings
  private[cluster] def failureDetector: FailureDetector
  private[cluster] def selfAddress: Address
  private[cluster] def scheduler: Scheduler
  private[cluster] def seedNodes: IndexedSeq[Address]
  private[cluster] def shutdown(): Unit
}

/**
 * INTERNAL API.
 *
 * Supervisor managing the different Cluster daemons.
 */
private[cluster] final class ClusterDaemon(environment: ClusterEnvironment) extends Actor with ActorLogging {

  val configuredDispatcher = environment.settings.UseDispatcher
  val core = context.actorOf(Props(new ClusterCoreDaemon(environment)).
    withDispatcher(configuredDispatcher), name = "core")
  val heartbeat = context.actorOf(Props(new ClusterHeartbeatDaemon(environment)).
    withDispatcher(configuredDispatcher), name = "heartbeat")

  def receive = {
    case InternalClusterAction.GetClusterCoreRef ⇒ sender ! core
  }

}

/**
 * INTERNAL API.
 */
private[cluster] final class ClusterCoreDaemon(environment: ClusterEnvironment) extends Actor with ActorLogging {
  import ClusterLeaderAction._
  import InternalClusterAction._
  import ClusterHeartbeatSender._

  def selfAddress = environment.selfAddress
  def clusterScheduler = environment.scheduler
  def failureDetector = environment.failureDetector
  val settings = environment.settings
  import settings._

  val vclockNode = VectorClock.Node(selfAddress.toString)
  val selfHeartbeat = Heartbeat(selfAddress)

  // note that self is not initially member,
  // and the Gossip is not versioned for this 'Node' yet
  var latestGossip: Gossip = Gossip()
  var joinInProgress: Map[Address, Deadline] = Map.empty

  var stats = ClusterStats()

  val heartbeatSender = context.actorOf(Props(new ClusterHeartbeatSender(environment)).
    withDispatcher(UseDispatcher), name = "heartbeatSender")
  val coreSender = context.actorOf(Props(new ClusterCoreSender(selfAddress)).
    withDispatcher(UseDispatcher), name = "coreSender")
  val publisher = context.actorOf(Props(new ClusterDomainEventPublisher(environment)).
    withDispatcher(UseDispatcher), name = "publisher")

  import context.dispatcher

  // start periodic gossip to random nodes in cluster
  val gossipTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(GossipInterval), GossipInterval) {
      self ! GossipTick
    }

  // start periodic heartbeat to all nodes in cluster
  val heartbeatTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(HeartbeatInterval), HeartbeatInterval) {
      self ! HeartbeatTick
    }

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  val failureDetectorReaperTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(UnreachableNodesReaperInterval), UnreachableNodesReaperInterval) {
      self ! ReapUnreachableTick
    }

  // start periodic leader action management (only applies for the current leader)
  private val leaderActionsTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(LeaderActionsInterval), LeaderActionsInterval) {
      self ! LeaderActionsTick
    }

  // start periodic publish of current state
  private val publishStateTask: Option[Cancellable] =
    if (PublishStatsInterval == Duration.Zero) None
    else Some(FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(PublishStatsInterval), PublishStatsInterval) {
      self ! PublishStatsTick
    })

  override def preStart(): Unit = {
    if (AutoJoin) {
      // only the node which is named first in the list of seed nodes will join itself
      if (environment.seedNodes.isEmpty || environment.seedNodes.head == selfAddress)
        self ! JoinTo(selfAddress)
      else
        context.actorOf(Props(new JoinSeedNodeProcess(environment)).
          withDispatcher(UseDispatcher), name = "joinSeedNodeProcess")
    }
  }

  override def postStop(): Unit = {
    gossipTask.cancel()
    heartbeatTask.cancel()
    failureDetectorReaperTask.cancel()
    leaderActionsTask.cancel()
    publishStateTask foreach { _.cancel() }
  }

  def uninitialized: Actor.Receive = {
    case InitJoin                 ⇒ // skip, not ready yet
    case JoinTo(address)          ⇒ join(address)
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick                  ⇒ // ignore periodic tasks until initialized
  }

  def initialized: Actor.Receive = {
    case msg: GossipEnvelope              ⇒ receiveGossip(msg)
    case msg: GossipMergeConflict         ⇒ receiveGossipMerge(msg)
    case GossipTick                       ⇒ gossip()
    case HeartbeatTick                    ⇒ heartbeat()
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
    case p: Ping                          ⇒ ping(p)

  }

  def removed: Actor.Receive = {
    case msg: SubscriptionMessage ⇒ publisher forward msg
    case _: Tick                  ⇒ // ignore periodic tasks
  }

  def receive = uninitialized

  def initJoin(): Unit = sender ! InitJoinAck(selfAddress)

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: Address): Unit = {
    if (!latestGossip.members.exists(_.address == address)) {
      val localGossip = latestGossip
      // wipe our state since a node that joins a cluster must be empty
      latestGossip = Gossip()
      joinInProgress = Map(address -> (Deadline.now + JoinTimeout))

      // wipe the failure detector since we are starting fresh and shouldn't care about the past
      failureDetector.reset()

      publish(localGossip)

      context.become(initialized)
      if (address == selfAddress)
        joining(address)
      else
        coreSender ! SendClusterMessage(address, ClusterUserAction.Join(selfAddress))
    }
  }

  /**
   * State transition to JOINING - new node joining.
   */
  def joining(node: Address): Unit = {
    val localGossip = latestGossip
    val localMembers = localGossip.members
    val localUnreachable = localGossip.overview.unreachable

    val alreadyMember = localMembers.exists(_.address == node)
    val isUnreachable = localGossip.overview.isNonDownUnreachable(node)

    if (!alreadyMember && !isUnreachable) {

      // remove the node from the 'unreachable' set in case it is a DOWN node that is rejoining cluster
      val (rejoiningMember, newUnreachableMembers) = localUnreachable partition { _.address == node }
      val newOverview = localGossip.overview copy (unreachable = newUnreachableMembers)

      // remove the node from the failure detector if it is a DOWN node that is rejoining cluster
      if (rejoiningMember.nonEmpty) failureDetector.remove(node)

      // add joining node as Joining
      // add self in case someone else joins before self has joined (Set discards duplicates)
      val newMembers = localMembers + Member(node, Joining) + Member(selfAddress, Joining)
      val newGossip = localGossip copy (overview = newOverview, members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      latestGossip = seenVersionedGossip

      log.debug("Cluster Node [{}] - Node [{}] is JOINING", selfAddress, node)
      // treat join as initial heartbeat, so that it becomes unavailable if nothing more happens
      if (node != selfAddress) {
        failureDetector heartbeat node
        gossipTo(node)
      }

      publish(localGossip)
    }
  }

  /**
   * State transition to LEAVING.
   */
  def leaving(address: Address): Unit = {
    val localGossip = latestGossip
    if (localGossip.members.exists(_.address == address)) { // only try to update if the node is available (in the member ring)
      val newMembers = localGossip.members map { member ⇒ if (member.address == address) Member(address, Leaving) else member } // mark node as LEAVING
      val newGossip = localGossip copy (members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      latestGossip = seenVersionedGossip

      log.info("Cluster Node [{}] - Marked address [{}] as LEAVING", selfAddress, address)
      publish(localGossip)
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
    val localGossip = latestGossip
    // just cleaning up the gossip state
    latestGossip = Gossip()
    publish(localGossip)
    context.become(removed)
    // make sure the final (removed) state is published
    // before shutting down
    implicit val timeout = Timeout(5 seconds)
    publisher ? PublishDone onComplete { case _ ⇒ environment.shutdown() }
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

    publish(localGossip)
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
      // FIXME how should we handle this situation?
      log.debug("Received gossip with self as unreachable, from [{}]", from)

    } else if (!localGossip.overview.isNonDownUnreachable(from)) {

      // leader handles merge conflicts, or when they have different views of how is leader
      val handleMerge = localGossip.leader == Some(selfAddress) || localGossip.leader != remoteGossip.leader
      val conflict = remoteGossip.version <> localGossip.version

      if (conflict && !handleMerge) {
        // delegate merge resolution to leader to reduce number of simultaneous resolves,
        // which will result in new conflicts

        stats = stats.incrementMergeDetectedCount
        log.debug("Merge conflict [{}] detected [{}] <> [{}]", stats.mergeDetectedCount, selfAddress, from)

        stats = stats.incrementMergeConflictCount
        val rate = mergeRate(stats.mergeConflictCount)

        if (rate <= MaxGossipMergeRate)
          coreSender ! SendClusterMessage(to = localGossip.leader.get, msg = GossipMergeConflict(GossipEnvelope(selfAddress, localGossip), envelope))
        else
          log.debug("Skipping gossip merge conflict due to rate [{}] / s ", rate)

      } else {

        val winningGossip =
          if (conflict) (remoteGossip merge localGossip) :+ vclockNode // conflicting versions, merge, and new version
          else if (remoteGossip.version < localGossip.version) localGossip // local gossip is newer
          else remoteGossip // remote gossip is newer

        val newJoinInProgress =
          if (joinInProgress.isEmpty) joinInProgress
          else joinInProgress -- winningGossip.members.map(_.address) -- winningGossip.overview.unreachable.map(_.address)

        latestGossip = winningGossip seen selfAddress
        joinInProgress = newJoinInProgress

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
        publish(localGossip)

        if (envelope.conversation &&
          (conflict || (winningGossip ne remoteGossip) || (latestGossip ne remoteGossip))) {
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
      //   6. Updating the vclock version for the changes
      //   7. Updating the 'seen' table
      //   8. Try to update the state with the new gossip
      //   9. If success - run all the side-effecting processing

      val (
        newGossip: Gossip,
        hasChangedState: Boolean,
        upMembers,
        exitingMembers,
        removedMembers,
        unreachableButNotDownedMembers) =

        if (localGossip.convergence) {
          // we have convergence - so we can't have unreachable nodes

          // transform the node member ring
          val newMembers = localMembers collect {
            // 1. Move JOINING => UP (once all nodes have seen that this node is JOINING e.g. we have a convergence)
            case member if member.status == Joining ⇒ member copy (status = Up)
            // 2. Move LEAVING => EXITING (once we have a convergence on LEAVING *and* if we have a successful partition handoff)
            case member if member.status == Leaving && hasPartionHandoffCompletedSuccessfully ⇒ member copy (status = Exiting)
            // 3. Everyone else that is not Exiting stays as they are
            case member if member.status != Exiting ⇒ member
            // 4. Move EXITING => REMOVED - e.g. remove the nodes from the 'members' set/node ring and seen table
          }

          // ----------------------
          // 5. Store away all stuff needed for the side-effecting processing in 10.
          // ----------------------

          // Check for the need to do side-effecting on successful state change
          // Repeat the checking for transitions between JOINING -> UP, LEAVING -> EXITING, EXITING -> REMOVED
          // to check for state-changes and to store away removed and exiting members for later notification
          //    1. check for state-changes to update
          //    2. store away removed and exiting members so we can separate the pure state changes (that can be retried on collision) and the side-effecting message sending
          val (removedMembers, newMembers1) = localMembers partition (_.status == Exiting)

          val (upMembers, newMembers2) = newMembers1 partition (_.status == Joining)

          val exitingMembers = newMembers2 filter (_.status == Leaving && hasPartionHandoffCompletedSuccessfully)

          val hasChangedState = removedMembers.nonEmpty || upMembers.nonEmpty || exitingMembers.nonEmpty

          // removing REMOVED nodes from the 'seen' table
          val newSeen = localSeen -- removedMembers.map(_.address)

          // removing REMOVED nodes from the 'unreachable' set
          val newUnreachableMembers = localUnreachableMembers -- removedMembers

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (members = newMembers, overview = newOverview) // update gossip

          (newGossip, hasChangedState, upMembers, exitingMembers, removedMembers, Member.none)

        } else if (AutoDown) {
          // we don't have convergence - so we might have unreachable nodes

          // if 'auto-down' is turned on, then try to auto-down any unreachable nodes
          val newUnreachableMembers = localUnreachableMembers collect {
            // ----------------------
            // 6. Move UNREACHABLE => DOWN (auto-downing by leader)
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
        // 6. Updating the vclock version for the changes
        // ----------------------
        val versionedGossip = newGossip :+ vclockNode

        // ----------------------
        // 7. Updating the 'seen' table
        //    Unless the leader (this node) is part of the removed members, i.e. the leader have moved himself from EXITING -> REMOVED
        // ----------------------
        val seenVersionedGossip =
          if (removedMembers.exists(_.address == selfAddress)) versionedGossip
          else versionedGossip seen selfAddress

        // ----------------------
        // 8. Update the state with the new gossip
        // ----------------------
        latestGossip = seenVersionedGossip

        // ----------------------
        // 9. Run all the side-effecting processing
        // ----------------------

        // log the move of members from joining to up
        upMembers foreach { member ⇒ log.info("Cluster Node [{}] - Leader is moving node [{}] from JOINING to UP", selfAddress, member.address) }

        //  tell all removed members to remove and shut down themselves
        removedMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from EXITING to REMOVED - and removing node from node ring", selfAddress, address)
          coreSender ! SendClusterMessage(
            to = address,
            msg = ClusterLeaderAction.Remove(address))
        }

        //  tell all exiting members to exit
        exitingMembers foreach { member ⇒
          val address = member.address
          log.info("Cluster Node [{}] - Leader is moving node [{}] from LEAVING to EXITING", selfAddress, address)
          coreSender ! SendClusterMessage(
            to = address,
            msg = ClusterLeaderAction.Exit(address)) // FIXME should use ? to await completion of handoff?
        }

        // log the auto-downing of the unreachable nodes
        unreachableButNotDownedMembers foreach { member ⇒
          log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as DOWN", selfAddress, member.address)
        }

        publish(localGossip)
      }
    }
  }

  def heartbeat(): Unit = {
    removeOverdueJoinInProgress()

    val beatTo = latestGossip.members.toSeq.map(_.address) ++ joinInProgress.keys

    val deadline = Deadline.now + HeartbeatInterval
    beatTo.foreach { address ⇒ if (address != selfAddress) heartbeatSender ! SendHeartbeat(selfHeartbeat, address, deadline) }
  }

  /**
   * Removes overdue joinInProgress from State.
   */
  def removeOverdueJoinInProgress(): Unit = {
    joinInProgress --= joinInProgress collect { case (address, deadline) if deadline.isOverdue ⇒ address }
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

        publish(localGossip)
      }
    }
  }

  def seedNodes: IndexedSeq[Address] = environment.seedNodes

  def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None
    else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def isSingletonCluster: Boolean = latestGossip.isSingletonCluster

  def isAvailable: Boolean = latestGossip.isAvailable(selfAddress)

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return the used [[akka.actor.Address] if any
   */
  private def gossipToRandomNodeOf(addresses: IndexedSeq[Address]): Option[Address] = {
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

  def gossipTo(address: Address, gossipMsg: GossipEnvelope): Unit = if (address != selfAddress)
    coreSender ! SendClusterMessage(address, gossipMsg)

  def publish(oldGossip: Gossip): Unit = {
    publisher ! PublishChanges(oldGossip, latestGossip)
    if (PublishStatsInterval == Duration.Zero) publishInternalStats()
  }

  def publishInternalStats(): Unit = publisher ! CurrentInternalStats(stats)

  def ping(p: Ping): Unit = sender ! Pong(p)
}

/**
 * INTERNAL API.
 *
 * Sends InitJoinAck to all seed nodes (except itself) and expect
 * InitJoinAck reply back. The seed node that replied first
 * will be used, joined to. InitJoinAck replies received after the
 * first one are ignored.
 *
 * Retries if no InitJoinAck replies are received within the
 * SeedNodeTimeout.
 * When at least one reply has been received it stops itself after
 * an idle SeedNodeTimeout.
 *
 */
private[cluster] final class JoinSeedNodeProcess(environment: ClusterEnvironment) extends Actor with ActorLogging {
  import InternalClusterAction._

  def selfAddress = environment.selfAddress

  if (environment.seedNodes.isEmpty || environment.seedNodes.head == selfAddress)
    throw new IllegalArgumentException("Join seed node should not be done")

  context.setReceiveTimeout(environment.settings.SeedNodeTimeout)

  override def preStart(): Unit = self ! JoinSeedNode

  def receive = {
    case JoinSeedNode ⇒
      // send InitJoin to all seed nodes (except myself)
      environment.seedNodes.collect {
        case a if a != selfAddress ⇒ context.system.actorFor(context.parent.path.toStringWithAddress(a))
      } foreach { _ ! InitJoin }
    case InitJoinAck(address) ⇒
      // first InitJoinAck reply
      context.parent ! JoinTo(address)
      context.become(done)
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
 * INTERNAL API.
 */
private[cluster] final class ClusterCoreSender(selfAddress: Address) extends Actor with ActorLogging {
  import InternalClusterAction._

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCoreConnectionFor(address: Address): ActorRef =
    context.system.actorFor(RootActorPath(address) / "system" / "cluster" / "core")

  def receive = {
    case SendClusterMessage(to, msg) ⇒
      log.debug("Cluster Node [{}] - Trying to send [{}] to [{}]", selfAddress, msg.getClass.getSimpleName, to)
      clusterCoreConnectionFor(to) ! msg
  }
}

/**
 * INTERNAL API
 */
private[cluster] case class ClusterStats(
  receivedGossipCount: Long = 0L,
  mergeConflictCount: Long = 0L,
  mergeCount: Long = 0L,
  mergeDetectedCount: Long = 0L) {

  def incrementReceivedGossipCount(): ClusterStats =
    copy(receivedGossipCount = receivedGossipCount + 1)

  def incrementMergeConflictCount(): ClusterStats =
    copy(mergeConflictCount = mergeConflictCount + 1)

  def incrementMergeCount(): ClusterStats =
    copy(mergeCount = mergeCount + 1)

  def incrementMergeDetectedCount(): ClusterStats =
    copy(mergeDetectedCount = mergeDetectedCount + 1)
}