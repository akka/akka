/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import akka.actor.Status._
import akka.remote._
import akka.routing._
import akka.event.Logging
import akka.dispatch.Await
import akka.pattern.ask
import akka.util._
import akka.util.duration._
import akka.config.ConfigurationException

import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import java.util.concurrent.TimeUnit._
import java.util.concurrent.TimeoutException
import java.security.SecureRandom

import java.lang.management.ManagementFactory
import javax.management._

import scala.collection.immutable.{ Map, SortedSet }
import scala.annotation.tailrec

import com.google.protobuf.ByteString

/**
 * Interface for membership change listener.
 */
trait MembershipChangeListener {
  def notify(members: SortedSet[Member]): Unit
}

/**
 * Interface for meta data change listener.
 */
trait MetaDataChangeListener {
  def notify(meta: Map[String, Array[Byte]]): Unit
}

/**
 * Base trait for all cluster messages. All ClusterMessage's are serializable.
 */
sealed trait ClusterMessage extends Serializable

/**
 * Cluster commands sent by the USER.
 */
object ClusterAction {

  /**
   * Command to join the cluster. Sent when a node (reprsesented by 'address')
   * wants to join another node (the receiver).
   */
  case class Join(address: Address) extends ClusterMessage

  /**
   * Command to set a node to Up (from Joining).
   */
  case class Up(address: Address) extends ClusterMessage

  /**
   * Command to leave the cluster.
   */
  case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  case class Down(address: Address) extends ClusterMessage

  /**
   * Command to mark a node to be removed from the cluster immediately.
   */
  case class Exit(address: Address) extends ClusterMessage

  /**
   * Command to remove a node from the cluster immediately.
   */
  case class Remove(address: Address) extends ClusterMessage
}

/**
 * Represents the address and the current status of a cluster member node.
 */
class Member(val address: Address, val status: MemberStatus) extends ClusterMessage {
  override def hashCode = address.##
  override def equals(other: Any) = Member.unapply(this) == Member.unapply(other)
  override def toString = "Member(address = %s, status = %s)" format (address, status)
  def copy(address: Address = this.address, status: MemberStatus = this.status): Member = new Member(address, status)
}

/**
 * Factory and Utility module for Member instances.
 */
object Member {
  import MemberStatus._

  implicit val ordering = Ordering.fromLessThan[Member](_.address.toString < _.address.toString)

  def apply(address: Address, status: MemberStatus): Member = new Member(address, status)

  def unapply(other: Any) = other match {
    case m: Member ⇒ Some(m.address)
    case _         ⇒ None
  }

  /**
   * Picks the Member with the highest "priority" MemberStatus.
   */
  def highestPriorityOf(m1: Member, m2: Member): Member = (m1.status, m2.status) match {
    case (Removed, _)       ⇒ m1
    case (_, Removed)       ⇒ m2
    case (Down, _)          ⇒ m1
    case (_, Down)          ⇒ m2
    case (Exiting, _)       ⇒ m1
    case (_, Exiting)       ⇒ m2
    case (Leaving, _)       ⇒ m1
    case (_, Leaving)       ⇒ m2
    case (Up, Joining)      ⇒ m1
    case (Joining, Up)      ⇒ m2
    case (Joining, Joining) ⇒ m1
    case (Up, Up)           ⇒ m1
  }
}

/**
 * Envelope adding a sender address to the gossip.
 */
case class GossipEnvelope(sender: Member, gossip: Gossip) extends ClusterMessage

/**
 * Defines the current status of a cluster member node
 *
 * Can be one of: Joining, Up, Leaving, Exiting and Down.
 */
sealed trait MemberStatus extends ClusterMessage
object MemberStatus {
  case object Joining extends MemberStatus
  case object Up extends MemberStatus
  case object Leaving extends MemberStatus
  case object Exiting extends MemberStatus
  case object Down extends MemberStatus
  case object Removed extends MemberStatus

  def isUnavailable(status: MemberStatus): Boolean = {
    status == MemberStatus.Down ||
      status == MemberStatus.Exiting ||
      status == MemberStatus.Removed ||
      status == MemberStatus.Leaving
  }
}

/**
 * Represents the overview of the cluster, holds the cluster convergence table and set with unreachable nodes.
 */
case class GossipOverview(
  seen: Map[Address, VectorClock] = Map.empty[Address, VectorClock],
  unreachable: Set[Member] = Set.empty[Member]) {

  // FIXME document when nodes are put in 'unreachable' set and removed from 'members'

  override def toString =
    "GossipOverview(seen = [" + seen.mkString(", ") +
      "], unreachable = [" + unreachable.mkString(", ") +
      "])"
}

/**
 * Represents the state of the cluster; cluster ring membership, ring convergence, meta data - all versioned by a vector clock.
 */
case class Gossip(
  overview: GossipOverview = GossipOverview(),
  members: SortedSet[Member], // sorted set of members with their status, sorted by name
  meta: Map[String, Array[Byte]] = Map.empty[String, Array[Byte]],
  version: VectorClock = VectorClock()) // vector clock version
  extends ClusterMessage // is a serializable cluster message
  with Versioned[Gossip] {

  /**
   * Increments the version for this 'Node'.
   */
  def +(node: VectorClock.Node): Gossip = copy(version = version + node)

  def +(member: Member): Gossip = {
    if (members contains member) this
    else this copy (members = members + member)
  }

  /**
   * Marks the gossip as seen by this node (remoteAddress) by updating the address entry in the 'gossip.overview.seen'
   * Map with the VectorClock for the new gossip.
   */
  def seen(address: Address): Gossip = {
    if (overview.seen.contains(address) && overview.seen(address) == version) this
    else this copy (overview = overview copy (seen = overview.seen + (address -> version)))
  }

  /**
   * Merges two Gossip instances including membership tables, meta-data tables and the VectorClock histories.
   */
  def merge(that: Gossip): Gossip = {
    import Member.ordering

    // 1. merge vector clocks
    val mergedVClock = this.version merge that.version

    // 2. group all members by Address => Vector[Member]
    var membersGroupedByAddress = Map.empty[Address, Vector[Member]]
    (this.members ++ that.members) foreach { m ⇒
      val ms = membersGroupedByAddress.get(m.address).getOrElse(Vector.empty[Member])
      membersGroupedByAddress += (m.address -> (ms :+ m))
    }

    // 3. merge members by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedMembers =
      SortedSet.empty[Member] ++
        membersGroupedByAddress.values.foldLeft(Vector.empty[Member]) { (acc, members) ⇒
          acc :+ members.reduceLeft(Member.highestPriorityOf(_, _))
        }

    // 4. merge meta-data
    val mergedMeta = this.meta ++ that.meta

    // 5. merge gossip overview
    val mergedOverview = GossipOverview(
      this.overview.seen ++ that.overview.seen,
      this.overview.unreachable ++ that.overview.unreachable)

    Gossip(mergedOverview, mergedMembers, mergedMeta, mergedVClock)
  }

  override def toString =
    "Gossip(" +
      "overview = " + overview +
      ", members = [" + members.mkString(", ") +
      "], meta = [" + meta.mkString(", ") +
      "], version = " + version +
      ")"
}

/**
 * Manages routing of the different cluster commands.
 * Instantiated as a single instance for each Cluster - e.g. commands are serialized to Cluster message after message.
 */
final class ClusterCommandDaemon extends Actor {
  import ClusterAction._

  val cluster = Cluster(context.system)
  val log = Logging(context.system, this)

  def receive = {
    case Join(address)   ⇒ cluster.joining(address)
    case Up(address)     ⇒ cluster.up(address)
    case Down(address)   ⇒ cluster.downing(address)
    case Leave(address)  ⇒ cluster.leaving(address)
    case Exit(address)   ⇒ cluster.exiting(address)
    case Remove(address) ⇒ cluster.removing(address)
  }

  override def unhandled(unknown: Any) = log.error("Illegal command [{}]", unknown)
}

/**
 * Pooled and routed with N number of configurable instances.
 * Concurrent access to Cluster.
 */
final class ClusterGossipDaemon extends Actor {
  val log = Logging(context.system, this)
  val cluster = Cluster(context.system)

  def receive = {
    case GossipEnvelope(sender, gossip) ⇒ cluster.receive(sender, gossip)
  }

  override def unhandled(unknown: Any) = log.error("[/system/cluster/gossip] can not respond to messages - received [{}]", unknown)
}

/**
 * Supervisor managing the different Cluster daemons.
 */
final class ClusterDaemonSupervisor extends Actor {
  val log = Logging(context.system, this)
  val cluster = Cluster(context.system)

  private val commands = context.actorOf(Props[ClusterCommandDaemon], "commands")
  private val gossip = context.actorOf(
    Props[ClusterGossipDaemon].withRouter(RoundRobinRouter(cluster.clusterSettings.NrOfGossipDaemons)), "gossip")

  def receive = Actor.emptyBehavior

  override def unhandled(unknown: Any): Unit = log.error("[/system/cluster] can not respond to messages - received [{}]", unknown)
}

/**
 * Cluster Extension Id and factory for creating Cluster extension.
 * Example:
 * {{{
 *  if (Cluster(system).isLeader) { ... }
 * }}}
 */
object Cluster extends ExtensionId[Cluster] with ExtensionIdProvider {
  override def get(system: ActorSystem): Cluster = super.get(system)

  override def lookup = Cluster

  override def createExtension(system: ExtendedActorSystem): Cluster = new Cluster(system)
}

/**
 * Interface for the cluster JMX MBean.
 */
trait ClusterNodeMBean {
  def getMemberStatus: String
  def getClusterStatus: String
  def getLeader: String

  def isSingleton: Boolean
  def isConvergence: Boolean
  def isAvailable: Boolean

  def join(address: String)
  def leave(address: String)
  def down(address: String)
  def remove(address: String)

  def shutdown()
}

/**
 * This module is responsible for Gossiping cluster information. The abstraction maintains the list of live
 * and dead members. Periodically i.e. every 1 second this module chooses a random member and initiates a round
 * of Gossip with it. Whenever it gets gossip updates it updates the Failure Detector with the liveness
 * information.
 * <p/>
 * During each of these runs the member initiates gossip exchange according to following rules (as defined in the
 * Cassandra documentation [http://wiki.apache.org/cassandra/ArchitectureGossip]:
 * <pre>
 *   1) Gossip to random live member (if any)
 *   2) Gossip to random unreachable member with certain probability depending on number of unreachable and live members
 *   3) If the member gossiped to at (1) was not deputy, or the number of live members is less than number of deputy list,
 *       gossip to random deputy with certain probability depending on number of unreachable, deputy and live members.
 * </pre>
 *
 * Example:
 * {{{
 *  if (Cluster(system).isLeader) { ... }
 * }}}
 */
class Cluster(system: ExtendedActorSystem) extends Extension { clusterNode ⇒

  /**
   * Represents the state for this Cluster. Implemented using optimistic lockless concurrency.
   * All state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    latestGossip: Gossip,
    memberMembershipChangeListeners: Set[MembershipChangeListener] = Set.empty[MembershipChangeListener])

  if (!system.provider.isInstanceOf[RemoteActorRefProvider])
    throw new ConfigurationException("ActorSystem[" + system + "] needs to have a 'RemoteActorRefProvider' enabled in the configuration")

  private val remote: RemoteActorRefProvider = system.provider.asInstanceOf[RemoteActorRefProvider]

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val clusterSettings = new ClusterSettings(system.settings.config, system.name)

  val remoteAddress = remote.transport.address
  val failureDetector = new AccrualFailureDetector(
    system, remoteAddress, clusterSettings.FailureDetectorThreshold, clusterSettings.FailureDetectorMaxSampleSize)

  private val vclockNode = VectorClock.Node(remoteAddress.toString)

  private val periodicTasksInitialDelay = clusterSettings.PeriodicTasksInitialDelay
  private val gossipFrequency = clusterSettings.GossipFrequency
  private val leaderActionsFrequency = clusterSettings.LeaderActionsFrequency
  private val unreachableNodesReaperFrequency = clusterSettings.UnreachableNodesReaperFrequency

  implicit private val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val autoDown = clusterSettings.AutoDown
  private val nrOfDeputyNodes = clusterSettings.NrOfDeputyNodes
  private val nrOfGossipDaemons = clusterSettings.NrOfGossipDaemons
  private val nodeToJoin: Option[Address] = clusterSettings.NodeToJoin filter (_ != remoteAddress)

  private val serialization = remote.serialization

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Node")
  private val random = SecureRandom.getInstance("SHA1PRNG")

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")

  log.info("Cluster Node [{}] - is starting up...", remoteAddress)

  // create superisor for daemons under path "/system/cluster"
  private val clusterDaemons = {
    val createChild = CreateChild(Props[ClusterDaemonSupervisor], "cluster")
    Await.result(system.systemGuardian ? createChild, defaultTimeout.duration) match {
      case a: ActorRef  ⇒ a
      case e: Exception ⇒ throw e
    }
  }

  private val state = {
    val member = Member(remoteAddress, MemberStatus.Joining)
    val gossip = Gossip(members = SortedSet.empty[Member] + member) + vclockNode // add me as member and update my vector clock
    new AtomicReference[State](State(gossip))
  }

  // try to join the node defined in the 'akka.cluster.node-to-join' option
  autoJoin()

  // ========================================================
  // ===================== WORK DAEMONS =====================
  // ========================================================

  // start periodic gossip to random nodes in cluster
  private val gossipCanceller = system.scheduler.schedule(periodicTasksInitialDelay, gossipFrequency) {
    gossip()
  }

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  private val failureDetectorReaperCanceller = system.scheduler.schedule(periodicTasksInitialDelay, unreachableNodesReaperFrequency) {
    reapUnreachableMembers()
  }

  // start periodic leader action management (only applies for the current leader)
  private val leaderActionsCanceller = system.scheduler.schedule(periodicTasksInitialDelay, leaderActionsFrequency) {
    leaderActions()
  }

  createMBean()

  log.info("Cluster Node [{}] - has started up successfully", remoteAddress)

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  def self: Member = latestGossip.members
    .find(_.address == remoteAddress)
    .getOrElse(throw new IllegalStateException("Can't find 'this' Member (" + remoteAddress + ") in the cluster membership ring"))

  /**
   * Latest gossip.
   */
  def latestGossip: Gossip = state.get.latestGossip

  /**
   * Member status for this node.
   */
  def status: MemberStatus = self.status

  /**
   * Is this node the leader?
   */
  def isLeader: Boolean = {
    val members = latestGossip.members
    !members.isEmpty && (remoteAddress == members.head.address)
  }

  /**
   * Get the address of the current leader.
   */
  def leader: Address = latestGossip.members.head.address

  /**
   * Is this node a singleton cluster?
   */
  def isSingletonCluster: Boolean = isSingletonCluster(state.get)

  /**
   * Checks if we have a cluster convergence.
   *
   * @returns Some(convergedGossip) if convergence have been reached and None if not
   */
  def convergence: Option[Gossip] = convergence(latestGossip)

  /**
   * Returns true if the node is UP or JOINING.
   */
  def isAvailable: Boolean = !isUnavailable(state.get)

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    if (isRunning.compareAndSet(true, false)) {
      log.info("Cluster Node [{}] - Shutting down cluster Node and cluster daemons...", remoteAddress)
      gossipCanceller.cancel()
      failureDetectorReaperCanceller.cancel()
      leaderActionsCanceller.cancel()
      system.stop(clusterDaemons)
      try {
        mBeanServer.unregisterMBean(clusterMBeanName)
      } catch {
        case e: InstanceNotFoundException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
      }
    }
  }

  /**
   * Registers a listener to subscribe to cluster membership changes.
   */
  @tailrec
  final def registerListener(listener: MembershipChangeListener) {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners + listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) registerListener(listener) // recur
  }

  /**
   * Unsubscribes to cluster membership changes.
   */
  @tailrec
  final def unregisterListener(listener: MembershipChangeListener) {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners - listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: Address) {
    val connection = clusterCommandConnectionFor(address)
    val command = ClusterAction.Join(remoteAddress)
    log.info("Cluster Node [{}] - Trying to send JOIN to [{}] through connection [{}]", remoteAddress, address, connection)
    connection ! command
  }

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   */
  def leave(address: Address) {
    clusterCommandDaemon ! ClusterAction.Leave(address)
  }

  /**
   * Send command to issue state transition to from DOWN to EXITING for the node specified by 'address'.
   */
  def down(address: Address) {
    clusterCommandDaemon ! ClusterAction.Down(address)
  }

  /**
   * Send command to issue state transition to REMOVED for the node specified by 'address'.
   */
  def remove(address: Address) {
    clusterCommandDaemon ! ClusterAction.Remove(address)
  }

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * State transition to JOINING.
   * New node joining.
   */
  @tailrec
  private[cluster] final def joining(node: Address) {
    log.info("Cluster Node [{}] - Node [{}] is JOINING", remoteAddress, node)

    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localUnreachableMembers = localOverview.unreachable

    // remove the node from the 'unreachable' set in case it is a DOWN node that is rejoining cluster
    val newUnreachableMembers = localUnreachableMembers filterNot { _.address == node }
    val newOverview = localOverview copy (unreachable = newUnreachableMembers)

    val newMembers = localMembers + Member(node, MemberStatus.Joining) // add joining node as Joining
    val newGossip = localGossip copy (overview = newOverview, members = newMembers)

    val versionedGossip = newGossip + vclockNode
    val seenVersionedGossip = versionedGossip seen remoteAddress

    val newState = localState copy (latestGossip = seenVersionedGossip)

    if (!state.compareAndSet(localState, newState)) joining(node) // recur if we failed update
    else {
      failureDetector heartbeat node // update heartbeat in failure detector
      if (convergence(newState.latestGossip).isDefined) {
        newState.memberMembershipChangeListeners foreach { _ notify newMembers }
      }
    }
  }

  /**
   * State transition to UP.
   */
  private[cluster] final def up(address: Address) {
    log.info("Cluster Node [{}] - Marking node [{}] as UP", remoteAddress, address)
  }

  /**
   * State transition to LEAVING.
   */
  private[cluster] final def leaving(address: Address) {
    log.info("Cluster Node [{}] - Marking node [{}] as LEAVING", remoteAddress, address)
  }

  /**
   * State transition to EXITING.
   */
  private[cluster] final def exiting(address: Address) {
    log.info("Cluster Node [{}] - Marking node [{}] as EXITING", remoteAddress, address)
  }

  /**
   * State transition to REMOVED.
   */
  private[cluster] final def removing(address: Address) {
    log.info("Cluster Node [{}] - Marking node [{}] as REMOVED", remoteAddress, address)
  }

  /**
   * The node to DOWN is removed from the 'members' set and put in the 'unreachable' set (if not alread there)
   * and its status is set to DOWN. The node is alo removed from the 'seen' table.
   *
   * The node will reside as DOWN in the 'unreachable' set until an explicit command JOIN command is sent directly
   * to this node and it will then go through the normal JOINING procedure.
   */
  @tailrec
  final private[cluster] def downing(address: Address) {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen
    val localUnreachableMembers = localOverview.unreachable

    // 1. check if the node to DOWN is in the 'members' set
    var downedMember: Option[Member] = None
    val newMembers =
      localMembers
        .map { member ⇒
          if (member.address == address) {
            log.info("Cluster Node [{}] - Marking node [{}] as DOWN", remoteAddress, member.address)
            val newMember = member copy (status = MemberStatus.Down)
            downedMember = Some(newMember)
            newMember
          } else member
        }
        .filter(_.status != MemberStatus.Down)

    // 2. check if the node to DOWN is in the 'unreachable' set
    val newUnreachableMembers =
      localUnreachableMembers
        .filter(_.status != MemberStatus.Down) // no need to DOWN members already DOWN
        .map { member ⇒
          if (member.address == address) {
            log.info("Cluster Node [{}] - Marking unreachable node [{}] as DOWN", remoteAddress, member.address)
            member copy (status = MemberStatus.Down)
          } else member
        }

    // 3. add the newly DOWNED members from the 'members' (in step 1.) to the 'newUnreachableMembers' set.
    val newUnreachablePlusNewlyDownedMembers = downedMember match {
      case Some(member) ⇒ newUnreachableMembers + member
      case None         ⇒ newUnreachableMembers
    }

    // 4. remove nodes marked as DOWN from the 'seen' table
    val newSeen = newUnreachablePlusNewlyDownedMembers.foldLeft(localSeen) { (currentSeen, member) ⇒
      currentSeen - member.address
    }

    val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachablePlusNewlyDownedMembers) // update gossip overview
    val newGossip = localGossip copy (overview = newOverview, members = newMembers) // update gossip
    val versionedGossip = newGossip + vclockNode
    val newState = localState copy (latestGossip = versionedGossip seen remoteAddress)

    if (!state.compareAndSet(localState, newState)) downing(address) // recur if we fail the update
    else {
      if (convergence(newState.latestGossip).isDefined) {
        newState.memberMembershipChangeListeners foreach { _ notify newState.latestGossip.members }
      }
    }
  }

  /**
   * Receive new gossip.
   */
  @tailrec
  final private[cluster] def receive(sender: Member, remoteGossip: Gossip) {
    val localState = state.get
    val localGossip = localState.latestGossip

    val winningGossip =
      if (remoteGossip.version <> localGossip.version) {
        // concurrent
        val mergedGossip = remoteGossip merge localGossip
        val versionedMergedGossip = mergedGossip + vclockNode

        log.debug(
          "Can't establish a causal relationship between \"remote\" gossip [{}] and \"local\" gossip [{}] - merging them into [{}]",
          remoteGossip, localGossip, versionedMergedGossip)

        versionedMergedGossip

      } else if (remoteGossip.version < localGossip.version) {
        // local gossip is newer
        localGossip

      } else {
        // remote gossip is newer
        remoteGossip
      }

    val newState = localState copy (latestGossip = winningGossip seen remoteAddress)

    // if we won the race then update else try again
    if (!state.compareAndSet(localState, newState)) receive(sender, remoteGossip) // recur if we fail the update
    else {
      log.debug("Cluster Node [{}] - Receiving gossip from [{}]", remoteAddress, sender.address)

      failureDetector heartbeat sender.address // update heartbeat in failure detector

      if (convergence(newState.latestGossip).isDefined) {
        newState.memberMembershipChangeListeners foreach { _ notify newState.latestGossip.members }
      }
    }
  }

  /**
   * Joins the pre-configured contact point and retrieves current gossip state.
   */
  private def autoJoin() = nodeToJoin foreach { address ⇒
    val connection = clusterCommandConnectionFor(address)
    val command = ClusterAction.Join(remoteAddress)
    log.info("Cluster Node [{}] - Sending [{}] to [{}] through connection [{}]", remoteAddress, command, address, connection)
    connection ! command
  }

  /**
   * Switches the member status.
   *
   * @param newStatus the new member status
   * @param oldState the state to change the member status in
   * @return the updated new state with the new member status
   */
  private def switchMemberStatusTo(newStatus: MemberStatus, state: State): State = {
    log.info("Cluster Node [{}] - Switching membership status to [{}]", remoteAddress, newStatus)

    val localSelf = self

    val localGossip = state.latestGossip
    val localMembers = localGossip.members

    // change my state into a "new" self
    val newSelf = localSelf copy (status = newStatus)

    // change my state in 'gossip.members'
    val newMembersSet = localMembers map { member ⇒
      if (member.address == remoteAddress) newSelf
      else member
    }

    // ugly crap to work around bug in scala colletions ('val ss: SortedSet[Member] = SortedSet.empty[Member] ++ aSet' does not compile)
    val newMembersSortedSet = SortedSet[Member](newMembersSet.toList: _*)
    val newGossip = localGossip copy (members = newMembersSortedSet)

    // version my changes
    val versionedGossip = newGossip + vclockNode
    val seenVersionedGossip = versionedGossip seen remoteAddress

    state copy (latestGossip = seenVersionedGossip)
  }

  /**
   * Gossips latest gossip to an address.
   */
  private def gossipTo(address: Address) {
    val connection = clusterGossipConnectionFor(address)
    log.debug("Cluster Node [{}] - Gossiping to [{}]", remoteAddress, connection)
    connection ! GossipEnvelope(self, latestGossip)
  }

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(addresses: Iterable[Address]): Boolean = {
    if (addresses.isEmpty) false
    else {
      val peers = addresses filter (_ != remoteAddress) // filter out myself
      val peer = selectRandomNode(peers)
      gossipTo(peer)
      deputyNodes exists (peer == _)
    }
  }

  /**
   * Initates a new round of gossip.
   */
  private def gossip() {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    if (!isSingletonCluster(localState) && isAvailable(localState)) {
      // only gossip if we are a non-singleton cluster and available

      log.debug("Cluster Node [{}] - Initiating new round of gossip", remoteAddress)

      val localGossip = localState.latestGossip
      val localMembers = localGossip.members
      val localMembersSize = localMembers.size

      val localUnreachableMembers = localGossip.overview.unreachable
      val localUnreachableSize = localUnreachableMembers.size

      // 1. gossip to alive members
      val gossipedToDeputy = gossipToRandomNodeOf(localMembers map { _.address })

      // 2. gossip to unreachable members
      if (localUnreachableSize > 0) {
        val probability: Double = localUnreachableSize / (localMembersSize + 1)
        if (random.nextDouble() < probability) gossipToRandomNodeOf(localUnreachableMembers.map(_.address))
      }

      // 3. gossip to a deputy nodes for facilitating partition healing
      val deputies = deputyNodes
      if ((!gossipedToDeputy || localMembersSize < 1) && !deputies.isEmpty) {
        if (localMembersSize == 0) gossipToRandomNodeOf(deputies)
        else {
          val probability = 1.0 / localMembersSize + localUnreachableSize
          if (random.nextDouble() <= probability) gossipToRandomNodeOf(deputies)
        }
      }
    }
  }

  /**
   * Reaps the unreachable members (moves them to the 'unreachable' list in the cluster overview) according to the failure detector's verdict.
   */
  @tailrec
  final private def reapUnreachableMembers() {
    val localState = state.get

    if (!isSingletonCluster(localState) && isAvailable(localState)) {
      // only scrutinize if we are a non-singleton cluster and available

      val localGossip = localState.latestGossip
      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localMembers = localGossip.members
      val localUnreachableMembers = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒ failureDetector.isAvailable(member.address) }

      if (!newlyDetectedUnreachableMembers.isEmpty) { // we have newly detected members marked as unavailable

        val newMembers = localMembers diff newlyDetectedUnreachableMembers
        val newUnreachableMembers: Set[Member] = localUnreachableMembers ++ newlyDetectedUnreachableMembers

        val newOverview = localOverview copy (unreachable = newUnreachableMembers)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        // updating vclock and 'seen' table
        val versionedGossip = newGossip + vclockNode
        val seenVersionedGossip = versionedGossip seen remoteAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) reapUnreachableMembers() // recur
        else {
          log.info("Cluster Node [{}] - Marking node(s) as UNREACHABLE [{}]", remoteAddress, newlyDetectedUnreachableMembers.mkString(", "))

          if (convergence(newState.latestGossip).isDefined) {
            newState.memberMembershipChangeListeners foreach { _ notify newMembers }
          }
        }
      }
    }
  }

  /**
   * Runs periodic leader actions, such as auto-downing unreachable nodes, assigning partitions etc.
   */
  @tailrec
  final private def leaderActions() {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val isLeader = !localMembers.isEmpty && (remoteAddress == localMembers.head.address)

    if (isLeader && isAvailable(localState)) {
      // only run the leader actions if we are the LEADER and available

      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localUnreachableMembers = localGossip.overview.unreachable

      // Leader actions are as follows:
      //   1. Move JOINING     => UP
      //   2. Move EXITING     => REMOVED
      //   3. Move UNREACHABLE => DOWN (auto-downing by leader)
      //   4. Updating the vclock version for the changes
      //   5. Updating the 'seen' table

      var hasChangedState = false
      val newGossip =

        if (convergence(localGossip).isDefined) {
          // we have convergence - so we can't have unreachable nodes

          val newMembers =
            localMembers map { member ⇒
              // 1. Move JOINING => UP
              if (member.status == MemberStatus.Joining) {
                log.info("Cluster Node [{}] - Leader is moving node [{}] from JOINING to UP", remoteAddress, member.address)
                hasChangedState = true
                member copy (status = MemberStatus.Up)
              } else member
            } map { member ⇒
              // 2. Move EXITING => REMOVED
              if (member.status == MemberStatus.Exiting) {
                log.info("Cluster Node [{}] - Leader is moving node [{}] from EXITING to REMOVED", remoteAddress, member.address)
                hasChangedState = true
                member copy (status = MemberStatus.Removed)
              } else member
            }
          localGossip copy (members = newMembers) // update gossip

        } else if (autoDown) {
          // we don't have convergence - so we might have unreachable nodes
          // if 'auto-down' is turned on, then try to auto-down any unreachable nodes

          // 3. Move UNREACHABLE => DOWN (auto-downing by leader)
          val newUnreachableMembers =
            localUnreachableMembers
              .filter(_.status != MemberStatus.Down) // no need to DOWN members already DOWN
              .map { member ⇒
                log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as DOWN", remoteAddress, member.address)
                hasChangedState = true
                member copy (status = MemberStatus.Down)
              }

          // removing nodes marked as DOWN from the 'seen' table
          val newSeen = localUnreachableMembers.foldLeft(localSeen)((currentSeen, member) ⇒ currentSeen - member.address)

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          localGossip copy (overview = newOverview) // update gossip

        } else localGossip

      if (hasChangedState) { // we have a change of state - version it and try to update

        // 4. Updating the vclock version for the changes
        val versionedGossip = newGossip + vclockNode

        // 5. Updating the 'seen' table
        val seenVersionedGossip = versionedGossip seen remoteAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) leaderActions() // recur
        else {
          if (convergence(newState.latestGossip).isDefined) {
            newState.memberMembershipChangeListeners map { _ notify newGossip.members }
          }
        }
      }
    }
  }

  /**
   * Checks if we have a cluster convergence. If there are any unreachable nodes then we can't have a convergence -
   * waiting for user to act (issuing DOWN) or leader to act (issuing DOWN through auto-down).
   *
   * @returns Some(convergedGossip) if convergence have been reached and None if not
   */
  private def convergence(gossip: Gossip): Option[Gossip] = {
    val overview = gossip.overview
    val unreachable = overview.unreachable

    // First check that:
    //   1. we don't have any members that are unreachable (unreachable.isEmpty == true), or
    //   2. all unreachable members in the set have status DOWN
    // Else we can't continue to check for convergence
    // When that is done we check that all the entries in the 'seen' table have the same vector clock version
    if (unreachable.isEmpty || !unreachable.exists(m ⇒ (m.status != MemberStatus.Down) && (m.status != MemberStatus.Removed))) {
      val seen = gossip.overview.seen
      val views = Set.empty[VectorClock] ++ seen.values

      if (views.size == 1) {
        log.debug("Cluster Node [{}] - Cluster convergence reached", remoteAddress)
        Some(gossip)
      } else None
    } else None
  }

  private def isAvailable(state: State): Boolean = !isUnavailable(state)

  private def isUnavailable(state: State): Boolean = {
    val localGossip = state.latestGossip
    val localOverview = localGossip.overview
    val localMembers = localGossip.members
    val localUnreachableMembers = localOverview.unreachable
    val isUnreachable = localUnreachableMembers exists { _.address == remoteAddress }
    val hasUnavailableMemberStatus = localMembers exists { m ⇒ (m == self) && MemberStatus.isUnavailable(m.status) }
    isUnreachable || hasUnavailableMemberStatus
  }

  /**
   * Looks up and returns the local cluster command connection.
   */
  private def clusterCommandDaemon = system.actorFor(RootActorPath(remoteAddress) / "system" / "cluster" / "commands")

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCommandConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "commands")

  /**
   * Looks up and returns the remote cluster gossip connection for the specific address.
   */
  private def clusterGossipConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "gossip")

  /**
   * Gets an Iterable with the addresses of a all the 'deputy' nodes - excluding this node if part of the group.
   */
  private def deputyNodes: Iterable[Address] = state.get.latestGossip.members.toIterable map (_.address) drop 1 take nrOfDeputyNodes filter (_ != remoteAddress)

  private def selectRandomNode(addresses: Iterable[Address]): Address = addresses.toSeq(random nextInt addresses.size)

  private def isSingletonCluster(currentState: State): Boolean = currentState.latestGossip.members.size == 1

  /**
   * Creates the cluster JMX MBean and registers it in the MBean server.
   */
  private def createMBean() = {
    val mbean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {

      // JMX attributes (bean-style)

      /*
       * Sends a string to the JMX client that will list all nodes in the node ring as follows:
       * {{{
       * Members:
       *         Member(address = akka://system0@localhost:5550, status = Up)
       *         Member(address = akka://system1@localhost:5551, status = Up)
       * Unreachable:
       *         Member(address = akka://system2@localhost:5553, status = Down)
       * }}}
       */
      def getClusterStatus: String = {
        val gossip = clusterNode.latestGossip
        val unreachable = gossip.overview.unreachable
        val metaData = gossip.meta
        "\nMembers:\n\t" + gossip.members.mkString("\n\t") +
          { if (!unreachable.isEmpty) "\nUnreachable:\n\t" + unreachable.mkString("\n\t") else "" } +
          { if (!metaData.isEmpty) "\nMeta Data:\t" + metaData.toString else "" }
      }

      def getMemberStatus: String = clusterNode.status.toString

      def getLeader: String = clusterNode.leader.toString

      def isSingleton: Boolean = clusterNode.isSingletonCluster

      def isConvergence: Boolean = clusterNode.convergence.isDefined

      def isAvailable: Boolean = clusterNode.isAvailable

      // JMX commands

      def join(address: String) = clusterNode.join(AddressFromURIString(address))

      def leave(address: String) = clusterNode.leave(AddressFromURIString(address))

      def down(address: String) = clusterNode.down(AddressFromURIString(address))

      def remove(address: String) = clusterNode.remove(AddressFromURIString(address))

      def shutdown() = clusterNode.shutdown()
    }
    log.info("Cluster Node [{}] - registering cluster JMX MBean [{}]", remoteAddress, clusterMBeanName)
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
    } catch {
      case e: InstanceAlreadyExistsException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }
}
