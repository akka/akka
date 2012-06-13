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
import akka.ConfigurationException
import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import java.util.concurrent.TimeUnit._
import java.util.concurrent.TimeoutException
import akka.jsr166y.ThreadLocalRandom
import java.lang.management.ManagementFactory
import java.io.Closeable
import javax.management._
import scala.collection.immutable.{ Map, SortedSet }
import scala.annotation.tailrec
import com.google.protobuf.ByteString
import akka.util.internal.HashedWheelTimer
import akka.dispatch.MonitorableThreadFactory
import MemberStatus._

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
 *
 * FIXME Protobuf all ClusterMessages
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
   * Command to leave the cluster.
   */
  case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  case class Down(address: Address) extends ClusterMessage

  /**
   * Command to remove a node from the cluster immediately.
   */
  case class Remove(address: Address) extends ClusterMessage

  /**
   * Command to mark a node to be removed from the cluster immediately.
   * Can only be sent by the leader.
   */
  private[akka] case class Exit(address: Address) extends ClusterMessage
}

/**
 * Represents the address and the current status of a cluster member node.
 *
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

  /**
   * Sort Address by host and port
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
    else if (a.port != b.port) a.port.getOrElse(0) < b.port.getOrElse(0)
    else false
  }

  implicit val ordering: Ordering[Member] = new Ordering[Member] {
    def compare(x: Member, y: Member) = addressOrdering.compare(x.address, y.address)
  }

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
case class GossipEnvelope(from: Address, gossip: Gossip) extends ClusterMessage

/**
 * Defines the current status of a cluster member node
 *
 * Can be one of: Joining, Up, Leaving, Exiting and Down.
 */
sealed trait MemberStatus extends ClusterMessage {
  /**
   * Using the same notion for 'unavailable' as 'non-convergence': DOWN and REMOVED.
   */
  def isUnavailable: Boolean = this == Down || this == Removed
}

object MemberStatus {
  case object Joining extends MemberStatus
  case object Up extends MemberStatus
  case object Leaving extends MemberStatus
  case object Exiting extends MemberStatus
  case object Down extends MemberStatus
  case object Removed extends MemberStatus
}

/**
 * Represents the overview of the cluster, holds the cluster convergence table and set with unreachable nodes.
 */
case class GossipOverview(
  seen: Map[Address, VectorClock] = Map.empty[Address, VectorClock],
  unreachable: Set[Member] = Set.empty[Member]) {

  override def toString =
    "GossipOverview(seen = [" + seen.mkString(", ") +
      "], unreachable = [" + unreachable.mkString(", ") +
      "])"
}

object Gossip {
  val emptyMembers: SortedSet[Member] = SortedSet.empty
}

/**
 * Represents the state of the cluster; cluster ring membership, ring convergence, meta data -
 * all versioned by a vector clock.
 *
 * When a node is joining the Member, with status Joining, is added to `members`.
 * If the joining node was downed it is moved from `overview.unreachable` (status Down)
 * to `members` (status Joining). It cannot rejoin if not first downed.
 *
 * When convergence is reached the leader change status of `members` from Joining
 * to Up.
 *
 * When failure detector consider a node as unavailble it will be moved from
 * `members` to `overview.unreachable`.
 *
 * When a node is downed, either manually or automatically, it is moved from `members`
 * to `overview.unreachable` (status Down). It is also removed from `overview.seen`
 * table. The node will reside as Down in the `overview.unreachable` set until joining
 * again and it will then go through the normal joining procedure.
 *
 * When a Gossip is received the version (vector clock) is used to determine if the
 * received Gossip is newer or older than the current local Gossip. The received Gossip
 * and local Gossip is merged in case of concurrent vector clocks, i.e. not same history.
 * When merged the seen table is cleared.
 *
 * TODO document leaving, exiting and removed when that is implemented
 *
 */
case class Gossip(
  overview: GossipOverview = GossipOverview(),
  members: SortedSet[Member], // sorted set of members with their status, sorted by address
  meta: Map[String, Array[Byte]] = Map.empty[String, Array[Byte]],
  version: VectorClock = VectorClock()) // vector clock version
  extends ClusterMessage // is a serializable cluster message
  with Versioned[Gossip] {

  // FIXME can be disabled as optimization
  assertInvariants
  private def assertInvariants: Unit = {
    val unreachableAndLive = members.intersect(overview.unreachable)
    if (unreachableAndLive.nonEmpty)
      throw new IllegalArgumentException("Same nodes in both members and unreachable is not allowed, got [%s]"
        format unreachableAndLive.mkString(", "))

    val allowedLiveMemberStatuses: Set[MemberStatus] = Set(Joining, Up, Leaving, Exiting)
    def hasNotAllowedLiveMemberStatus(m: Member) = !allowedLiveMemberStatuses.contains(m.status)
    if (members exists hasNotAllowedLiveMemberStatus)
      throw new IllegalArgumentException("Live members must have status [%s], got [%s]"
        format (allowedLiveMemberStatuses.mkString(", "),
          (members filter hasNotAllowedLiveMemberStatus).mkString(", ")))

    val seenButNotMember = overview.seen.keySet -- members.map(_.address) -- overview.unreachable.map(_.address)
    if (seenButNotMember.nonEmpty)
      throw new IllegalArgumentException("Nodes not part of cluster have marked the Gossip as seen, got [%s]"
        format seenButNotMember.mkString(", "))

  }

  /**
   * Increments the version for this 'Node'.
   */
  def +(node: VectorClock.Node): Gossip = copy(version = version + node)

  def +(member: Member): Gossip = {
    if (members contains member) this
    else this copy (members = members + member)
  }

  /**
   * Marks the gossip as seen by this node (selfAddress) by updating the address entry in the 'gossip.overview.seen'
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

    // 2. merge meta-data
    val mergedMeta = this.meta ++ that.meta

    def pickHighestPriority(a: Seq[Member], b: Seq[Member]): Set[Member] = {
      // group all members by Address => Seq[Member]
      val groupedByAddress = (a ++ b).groupBy(_.address)
      // pick highest MemberStatus
      (Set.empty[Member] /: groupedByAddress) {
        case (acc, (_, members)) ⇒ acc + members.reduceLeft(Member.highestPriorityOf)
      }
    }

    // 3. merge unreachable by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedUnreachable = pickHighestPriority(this.overview.unreachable.toSeq, that.overview.unreachable.toSeq)

    // 4. merge members by selecting the single Member with highest MemberStatus out of the Member groups,
    //    and exclude unreachable
    val mergedMembers = Gossip.emptyMembers ++ pickHighestPriority(this.members.toSeq, that.members.toSeq).
      filterNot(mergedUnreachable.contains)

    // 5. fresh seen table
    val mergedSeen = Map.empty[Address, VectorClock]

    Gossip(GossipOverview(mergedSeen, mergedUnreachable), mergedMembers, mergedMeta, mergedVClock)
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
 * Sent at regular intervals for failure detection.
 */
case class Heartbeat(from: Address) extends ClusterMessage

/**
 * Manages routing of the different cluster commands.
 * Instantiated as a single instance for each Cluster - e.g. commands are serialized to Cluster message after message.
 */
private[akka] final class ClusterCommandDaemon(cluster: Cluster) extends Actor {
  import ClusterAction._

  val log = Logging(context.system, this)

  def receive = {
    case Join(address)   ⇒ cluster.joining(address)
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
private[akka] final class ClusterGossipDaemon(cluster: Cluster) extends Actor {
  val log = Logging(context.system, this)

  def receive = {
    case Heartbeat(from)              ⇒ cluster.receiveHeartbeat(from)
    case GossipEnvelope(from, gossip) ⇒ cluster.receiveGossip(from, gossip)
  }

  override def unhandled(unknown: Any) = log.error("[/system/cluster/gossip] can not respond to messages - received [{}]", unknown)
}

/**
 * Supervisor managing the different Cluster daemons.
 */
private[akka] final class ClusterDaemonSupervisor(cluster: Cluster) extends Actor {
  val log = Logging(context.system, this)

  private val commands = context.actorOf(Props(new ClusterCommandDaemon(cluster)), "commands")
  private val gossip = context.actorOf(
    Props(new ClusterGossipDaemon(cluster)).withRouter(
      RoundRobinRouter(cluster.clusterSettings.NrOfGossipDaemons)), "gossip")

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

  override def createExtension(system: ExtendedActorSystem): Cluster = {
    val clusterSettings = new ClusterSettings(system.settings.config, system.name)

    val failureDetector = clusterSettings.FailureDetectorImplementationClass match {
      case None ⇒ new AccrualFailureDetector(system, clusterSettings)
      case Some(fqcn) ⇒
        system.dynamicAccess.createInstanceFor[FailureDetector](
          fqcn, Seq((classOf[ActorSystem], system), (classOf[ClusterSettings], clusterSettings))) match {
            case Right(fd) ⇒ fd
            case Left(e)   ⇒ throw new ConfigurationException("Could not create custom failure detector [" + fqcn + "] due to:" + e.toString)
          }
    }

    new Cluster(system, failureDetector)
  }
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
class Cluster(system: ExtendedActorSystem, val failureDetector: FailureDetector) extends Extension { clusterNode ⇒

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
  import clusterSettings._

  val selfAddress = remote.transport.address
  private val selfHeartbeat = Heartbeat(selfAddress)

  private val vclockNode = VectorClock.Node(selfAddress.toString)

  implicit private val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val nodeToJoin: Option[Address] = NodeToJoin filter (_ != selfAddress)

  private val serialization = remote.serialization

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Node")

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")

  log.info("Cluster Node [{}] - is starting up...", selfAddress)

  // create supervisor for daemons under path "/system/cluster"
  private val clusterDaemons = {
    val createChild = CreateChild(Props(new ClusterDaemonSupervisor(this)), "cluster")
    Await.result(system.systemGuardian ? createChild, defaultTimeout.duration) match {
      case a: ActorRef  ⇒ a
      case e: Exception ⇒ throw e
    }
  }

  private val state = {
    val member = Member(selfAddress, Joining)
    val versionedGossip = Gossip(members = Gossip.emptyMembers + member) + vclockNode // add me as member and update my vector clock
    val seenVersionedGossip = versionedGossip seen selfAddress
    new AtomicReference[State](State(seenVersionedGossip))
  }

  // try to join the node defined in the 'akka.cluster.node-to-join' option
  autoJoin()

  // ========================================================
  // ===================== WORK DAEMONS =====================
  // ========================================================

  private val clusterScheduler: Scheduler with Closeable = {
    if (system.settings.SchedulerTickDuration > SchedulerTickDuration) {
      log.info("Using a dedicated scheduler for cluster. Default scheduler can be used if configured " +
        "with 'akka.scheduler.tick-duration' [{} ms] <=  'akka.cluster.scheduler.tick-duration' [{} ms].",
        system.settings.SchedulerTickDuration.toMillis, SchedulerTickDuration.toMillis)
      val threadFactory = system.threadFactory match {
        case tf: MonitorableThreadFactory ⇒ tf.copy(name = tf.name + "-cluster-scheduler")
        case tf                           ⇒ tf
      }
      val hwt = new HashedWheelTimer(log,
        threadFactory,
        SchedulerTickDuration, SchedulerTicksPerWheel)
      new DefaultScheduler(hwt, log, system.dispatcher)
    } else {
      // delegate to system.scheduler, but don't close
      val systemScheduler = system.scheduler
      new Scheduler with Closeable {
        // we are using system.scheduler, which we are not responsible for closing
        def close(): Unit = ()
        def schedule(initialDelay: Duration, frequency: Duration, receiver: ActorRef, message: Any): Cancellable =
          systemScheduler.schedule(initialDelay, frequency, receiver, message)
        def schedule(initialDelay: Duration, frequency: Duration)(f: ⇒ Unit): Cancellable =
          systemScheduler.schedule(initialDelay, frequency)(f)
        def schedule(initialDelay: Duration, frequency: Duration, runnable: Runnable): Cancellable =
          systemScheduler.schedule(initialDelay, frequency, runnable)
        def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable =
          systemScheduler.scheduleOnce(delay, runnable)
        def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable =
          systemScheduler.scheduleOnce(delay, receiver, message)
        def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable =
          systemScheduler.scheduleOnce(delay)(f)
      }
    }
  }

  // start periodic gossip to random nodes in cluster
  private val gossipTask = FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay, GossipInterval) {
    gossip()
  }

  // start periodic heartbeat to all nodes in cluster
  private val heartbeatTask = FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay, HeartbeatInterval) {
    heartbeat()
  }

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  private val failureDetectorReaperTask = FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay, UnreachableNodesReaperInterval) {
    reapUnreachableMembers()
  }

  // start periodic leader action management (only applies for the current leader)
  private val leaderActionsTask = FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay, LeaderActionsInterval) {
    leaderActions()
  }

  createMBean()

  system.registerOnTermination(shutdown())

  log.info("Cluster Node [{}] - has started up successfully", selfAddress)

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  def self: Member = {
    val gossip = latestGossip
    gossip.members
      .find(_.address == selfAddress)
      .getOrElse {
        gossip.overview.unreachable
          .find(_.address == selfAddress)
          .getOrElse(throw new IllegalStateException("Can't find 'this' Member [" + selfAddress + "] in the cluster membership ring or in the unreachable set"))
      }
  }

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
    members.nonEmpty && (selfAddress == members.head.address)
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
   * @return Some(convergedGossip) if convergence have been reached and None if not
   */
  def convergence: Option[Gossip] = convergence(latestGossip)

  /**
   * Returns true if the node is UP or JOINING.
   */
  def isAvailable: Boolean = !isUnavailable(state.get)

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown(): Unit = {
    if (isRunning.compareAndSet(true, false)) {
      log.info("Cluster Node [{}] - Shutting down cluster Node and cluster daemons...", selfAddress)

      // cancel the periodic tasks, note that otherwise they will be run when scheduler is shutdown
      gossipTask.cancel()
      heartbeatTask.cancel()
      failureDetectorReaperTask.cancel()
      leaderActionsTask.cancel()
      clusterScheduler.close()

      // FIXME isTerminated check can be removed when ticket #2221 is fixed
      // now it prevents logging if system is shutdown (or in progress of shutdown)
      if (!clusterDaemons.isTerminated)
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
  final def registerListener(listener: MembershipChangeListener): Unit = {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners + listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) registerListener(listener) // recur
  }

  /**
   * Unsubscribes to cluster membership changes.
   */
  @tailrec
  final def unregisterListener(listener: MembershipChangeListener): Unit = {
    val localState = state.get
    val newListeners = localState.memberMembershipChangeListeners - listener
    val newState = localState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(localState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: Address): Unit = {
    val connection = clusterCommandConnectionFor(address)
    val command = ClusterAction.Join(selfAddress)
    log.info("Cluster Node [{}] - Trying to send JOIN to [{}] through connection [{}]", selfAddress, address, connection)
    connection ! command
  }

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   */
  def leave(address: Address): Unit = {
    clusterCommandDaemon ! ClusterAction.Leave(address)
  }

  /**
   * Send command to issue state transition to from DOWN to EXITING for the node specified by 'address'.
   */
  def down(address: Address): Unit = {
    clusterCommandDaemon ! ClusterAction.Down(address)
  }

  /**
   * Send command to issue state transition to REMOVED for the node specified by 'address'.
   */
  def remove(address: Address): Unit = {
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
  private[cluster] final def joining(node: Address): Unit = {
    log.info("Cluster Node [{}] - Node [{}] is JOINING", selfAddress, node)

    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members
    val localUnreachable = localGossip.overview.unreachable

    val alreadyMember = localMembers.exists(_.address == node)
    val isUnreachable = localUnreachable.exists { m ⇒
      m.address == node && m.status != Down && m.status != Removed
    }

    if (!alreadyMember && !isUnreachable) {

      // remove the node from the 'unreachable' set in case it is a DOWN node that is rejoining cluster
      val newUnreachableMembers = localUnreachable filterNot { _.address == node }
      val newOverview = localGossip.overview copy (unreachable = newUnreachableMembers)

      val newMembers = localMembers + Member(node, Joining) // add joining node as Joining
      val newGossip = localGossip copy (overview = newOverview, members = newMembers)

      val versionedGossip = newGossip + vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      val newState = localState copy (latestGossip = seenVersionedGossip)

      if (!state.compareAndSet(localState, newState)) joining(node) // recur if we failed update
      else {
        // treat join as initial heartbeat, so that it becomes unavailable if nothing more happens
        if (node != selfAddress) failureDetector heartbeat node
        notifyMembershipChangeListeners(localState, newState)
      }
    }
  }

  /**
   * State transition to LEAVING.
   */
  @tailrec
  private[cluster] final def leaving(address: Address) {
    log.info("Cluster Node [{}] - Marking address [{}] as LEAVING", selfAddress, address)

    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val newMembers = localMembers + Member(address, Leaving) // mark node as LEAVING
    val newGossip = localGossip copy (members = newMembers)

    val versionedGossip = newGossip + vclockNode
    val seenVersionedGossip = versionedGossip seen selfAddress

    val newState = localState copy (latestGossip = seenVersionedGossip)

    if (!state.compareAndSet(localState, newState)) leaving(address) // recur if we failed update
    else {
      notifyMembershipChangeListeners(localState, newState)
    }
  }

  private def notifyMembershipChangeListeners(oldState: State, newState: State): Unit = {
    val oldMembersStatus = oldState.latestGossip.members.toSeq.map(m ⇒ (m.address, m.status))
    val newMembersStatus = newState.latestGossip.members.toSeq.map(m ⇒ (m.address, m.status))
    if (newMembersStatus != oldMembersStatus)
      newState.memberMembershipChangeListeners foreach { _ notify newState.latestGossip.members }
  }

  /**
   * State transition to EXITING.
   */
  private[cluster] final def exiting(address: Address): Unit = {
    log.info("Cluster Node [{}] - Marking node [{}] as EXITING", selfAddress, address)
  }

  /**
   * State transition to REMOVED.
   */
  private[cluster] final def removing(address: Address): Unit = {
    log.info("Cluster Node [{}] - Marking node [{}] as REMOVED", selfAddress, address)
  }

  /**
   * The node to DOWN is removed from the 'members' set and put in the 'unreachable' set (if not already there)
   * and its status is set to DOWN. The node is also removed from the 'seen' table.
   *
   * The node will reside as DOWN in the 'unreachable' set until an explicit command JOIN command is sent directly
   * to this node and it will then go through the normal JOINING procedure.
   */
  @tailrec
  final private[cluster] def downing(address: Address): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members
    val localOverview = localGossip.overview
    val localSeen = localOverview.seen
    val localUnreachableMembers = localOverview.unreachable

    // 1. check if the node to DOWN is in the 'members' set
    val downedMember: Option[Member] = localMembers.collectFirst {
      case m if m.address == address ⇒ m.copy(status = Down)
    }
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
    val newSeen = localSeen -- newUnreachablePlusNewlyDownedMembers.collect {
      case m if m.status == Down ⇒ m.address
    }

    // update gossip overview
    val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachablePlusNewlyDownedMembers)
    val newGossip = localGossip copy (overview = newOverview, members = newMembers) // update gossip
    val versionedGossip = newGossip + vclockNode
    val newState = localState copy (latestGossip = versionedGossip seen selfAddress)

    if (!state.compareAndSet(localState, newState)) downing(address) // recur if we fail the update
    else {
      notifyMembershipChangeListeners(localState, newState)
    }
  }

  /**
   * Receive new gossip.
   */
  @tailrec
  final private[cluster] def receiveGossip(from: Address, remoteGossip: Gossip): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip

    val winningGossip =
      if (remoteGossip.version <> localGossip.version) {
        // concurrent
        val mergedGossip = remoteGossip merge localGossip
        val versionedMergedGossip = mergedGossip + vclockNode

        // FIXME change to debug log level, when failure detector is stable
        log.info(
          """Can't establish a causal relationship between "remote" gossip [{}] and "local" gossip [{}] - merging them into [{}]""",
          remoteGossip, localGossip, versionedMergedGossip)

        versionedMergedGossip

      } else if (remoteGossip.version < localGossip.version) {
        // local gossip is newer
        localGossip

      } else {
        // remote gossip is newer
        remoteGossip
      }

    val newState = localState copy (latestGossip = winningGossip seen selfAddress)

    // if we won the race then update else try again
    if (!state.compareAndSet(localState, newState)) receiveGossip(from, remoteGossip) // recur if we fail the update
    else {
      log.debug("Cluster Node [{}] - Receiving gossip from [{}]", selfAddress, from)
      notifyMembershipChangeListeners(localState, newState)
    }
  }

  /**
   * INTERNAL API
   */
  private[cluster] def receiveHeartbeat(from: Address): Unit = failureDetector heartbeat from

  /**
   * Joins the pre-configured contact point.
   */
  private def autoJoin(): Unit = nodeToJoin foreach join

  /**
   * INTERNAL API
   *
   * Gossips latest gossip to an address.
   */
  private[akka] def gossipTo(address: Address): Unit = {
    val connection = clusterGossipConnectionFor(address)
    log.debug("Cluster Node [{}] - Gossiping to [{}]", selfAddress, connection)
    connection ! GossipEnvelope(selfAddress, latestGossip)
  }

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return the used [[akka.actor.Address] if any
   */
  private def gossipToRandomNodeOf(addresses: IndexedSeq[Address]): Option[Address] = {
    log.debug("Cluster Node [{}] - Selecting random node to gossip to [{}]", selfAddress, addresses.mkString(", "))
    val peers = addresses filterNot (_ == selfAddress) // filter out myself
    val peer = selectRandomNode(peers)
    peer foreach gossipTo
    peer
  }

  /**
   * INTERNAL API
   */
  private[akka] def gossipToUnreachableProbablity(membersSize: Int, unreachableSize: Int): Double =
    (membersSize + unreachableSize) match {
      case 0   ⇒ 0.0
      case sum ⇒ unreachableSize.toDouble / sum
    }

  /**
   * INTERNAL API
   */
  private[akka] def gossipToDeputyProbablity(membersSize: Int, unreachableSize: Int, nrOfDeputyNodes: Int): Double = {
    if (nrOfDeputyNodes > membersSize) 1.0
    else if (nrOfDeputyNodes == 0) 0.0
    else (membersSize + unreachableSize) match {
      case 0   ⇒ 0.0
      case sum ⇒ (nrOfDeputyNodes + unreachableSize).toDouble / sum
    }
  }

  /**
   * INTERNAL API
   *
   * Initates a new round of gossip.
   */
  private[akka] def gossip(): Unit = {
    val localState = state.get

    log.debug("Cluster Node [{}] - Initiating new round of gossip", selfAddress)

    if (!isSingletonCluster(localState) && isAvailable(localState)) {
      val localGossip = localState.latestGossip
      // important to not accidentally use `map` of the SortedSet, since the original order is not preserved
      val localMembers = localGossip.members.toIndexedSeq
      val localMembersSize = localMembers.size
      val localMemberAddresses = localMembers map { _.address }

      val localUnreachableMembers = localGossip.overview.unreachable.toIndexedSeq
      val localUnreachableSize = localUnreachableMembers.size

      // 1. gossip to alive members
      val gossipedToAlive = gossipToRandomNodeOf(localMemberAddresses)

      // 2. gossip to unreachable members
      if (localUnreachableSize > 0) {
        val probability = gossipToUnreachableProbablity(localMembersSize, localUnreachableSize)
        if (ThreadLocalRandom.current.nextDouble() < probability)
          gossipToRandomNodeOf(localUnreachableMembers.map(_.address))
      }

      // 3. gossip to a deputy nodes for facilitating partition healing
      val deputies = deputyNodes(localMemberAddresses)
      val alreadyGossipedToDeputy = gossipedToAlive.map(deputies.contains(_)).getOrElse(false)
      if ((!alreadyGossipedToDeputy || localMembersSize < NrOfDeputyNodes) && deputies.nonEmpty) {
        val probability = gossipToDeputyProbablity(localMembersSize, localUnreachableSize, NrOfDeputyNodes)
        if (ThreadLocalRandom.current.nextDouble() < probability)
          gossipToRandomNodeOf(deputies)
      }
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] def heartbeat(): Unit = {
    val localState = state.get

    if (!isSingletonCluster(localState)) {
      val liveMembers = localState.latestGossip.members.toIndexedSeq

      for (member ← liveMembers; if member.address != selfAddress) {
        val connection = clusterGossipConnectionFor(member.address)
        log.debug("Cluster Node [{}] - Heartbeat to [{}]", selfAddress, connection)
        connection ! selfHeartbeat
      }
    }
  }

  /**
   * INTERNAL API
   *
   * Reaps the unreachable members (moves them to the 'unreachable' list in the cluster overview) according to the failure detector's verdict.
   */
  @tailrec
  final private[akka] def reapUnreachableMembers(): Unit = {
    val localState = state.get

    if (!isSingletonCluster(localState) && isAvailable(localState)) {
      // only scrutinize if we are a non-singleton cluster and available

      val localGossip = localState.latestGossip
      val localOverview = localGossip.overview
      val localMembers = localGossip.members
      val localUnreachableMembers = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒ failureDetector.isAvailable(member.address) }

      if (newlyDetectedUnreachableMembers.nonEmpty) { // we have newly detected members marked as unavailable

        val newMembers = localMembers -- newlyDetectedUnreachableMembers
        val newUnreachableMembers = localUnreachableMembers ++ newlyDetectedUnreachableMembers

        val newOverview = localOverview copy (unreachable = newUnreachableMembers)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        // updating vclock and 'seen' table
        val versionedGossip = newGossip + vclockNode
        val seenVersionedGossip = versionedGossip seen selfAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) reapUnreachableMembers() // recur
        else {
          log.info("Cluster Node [{}] - Marking node(s) as UNREACHABLE [{}]", selfAddress, newlyDetectedUnreachableMembers.mkString(", "))

          notifyMembershipChangeListeners(localState, newState)
        }
      }
    }
  }

  /**
   * INTERNAL API
   *
   * Runs periodic leader actions, such as auto-downing unreachable nodes, assigning partitions etc.
   */
  @tailrec
  final private[akka] def leaderActions(): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val isLeader = localMembers.nonEmpty && (selfAddress == localMembers.head.address)

    // FIXME implement partion handoff and a check if it is completed - now just returns TRUE - e.g. has completed successfully
    def hasPartionHandoffCompletedSuccessfully(gossip: Gossip): Boolean = {
      true
    }

    if (isLeader && isAvailable(localState)) {
      // only run the leader actions if we are the LEADER and available

      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localUnreachableMembers = localOverview.unreachable

      // Leader actions are as follows:
      //   1. Move JOINING     => UP          -- When a node joins the cluster
      //   2. Move EXITING     => REMOVED     -- When all nodes have seen that the node is EXITING (convergence)
      //   3. Move LEAVING     => EXITING     -- When all partition handoff has completed
      //   4. Move UNREACHABLE => DOWN        -- When the node is in the UNREACHABLE set it can be auto-down by leader
      //   5. Updating the vclock version for the changes
      //   6. Updating the 'seen' table

      var hasChangedState = false
      val newGossip =

        if (convergence(localGossip).isDefined) {
          // we have convergence - so we can't have unreachable nodes

          val newMembers =

            localMembers map { member ⇒
              // ----------------------
              // 1. Move JOINING => UP (once all nodes have seen that this node is JOINING e.g. we have a convergence)
              // ----------------------
              if (member.status == Joining) {
                log.info("Cluster Node [{}] - Leader is moving node [{}] from JOINING to UP", selfAddress, member.address)
                hasChangedState = true
                member copy (status = Up)
              } else member

            } map { member ⇒
              // ----------------------
              // 2. Move EXITING => REMOVED (once all nodes have seen that this node is EXITING e.g. we have a convergence)
              // ----------------------
              if (member.status == Exiting) {
                log.info("Cluster Node [{}] - Leader is moving node [{}] from EXITING to REMOVED", selfAddress, member.address)
                hasChangedState = true
                member copy (status = Removed)
              } else member

            } map { member ⇒
              // ----------------------
              // 3. Move LEAVING => EXITING (once we have a convergence on LEAVING *and* if we have a successful partition handoff)
              // ----------------------
              if (member.status == Leaving && hasPartionHandoffCompletedSuccessfully(localGossip)) {
                log.info("Cluster Node [{}] - Leader is moving node [{}] from LEAVING to EXITING", selfAddress, member.address)
                hasChangedState = true
                member copy (status = Exiting)
              } else member

            }
          localGossip copy (members = newMembers) // update gossip

        } else if (AutoDown) {
          // we don't have convergence - so we might have unreachable nodes
          // if 'auto-down' is turned on, then try to auto-down any unreachable nodes

          // ----------------------
          // 4. Move UNREACHABLE => DOWN (auto-downing by leader)
          // ----------------------
          val newUnreachableMembers =
            localUnreachableMembers.map { member ⇒
              // no need to DOWN members already DOWN
              if (member.status == Down) member
              else {
                log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as DOWN", selfAddress, member.address)
                hasChangedState = true
                member copy (status = Down)
              }
            }

          // removing nodes marked as DOWN from the 'seen' table
          val newSeen = localSeen -- newUnreachableMembers.collect {
            case m if m.status == Down ⇒ m.address
          }

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          localGossip copy (overview = newOverview) // update gossip

        } else localGossip

      if (hasChangedState) { // we have a change of state - version it and try to update

        // ----------------------
        // 5. Updating the vclock version for the changes
        // ----------------------
        val versionedGossip = newGossip + vclockNode

        // ----------------------
        // 6. Updating the 'seen' table
        // ----------------------
        val seenVersionedGossip = versionedGossip seen selfAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) leaderActions() // recur
        else {
          notifyMembershipChangeListeners(localState, newState)
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
    if (unreachable.isEmpty || !unreachable.exists { m ⇒
      m.status != Down && m.status != Removed
    }) {
      val seen = gossip.overview.seen
      val views = Set.empty[VectorClock] ++ seen.values

      if (views.size == 1) {
        log.debug("Cluster Node [{}] - Cluster convergence reached: [{}]", selfAddress, gossip.members.mkString(", "))
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
    val isUnreachable = localUnreachableMembers exists { _.address == selfAddress }
    val hasUnavailableMemberStatus = localMembers exists { m ⇒ (m == self) && m.status.isUnavailable }
    isUnreachable || hasUnavailableMemberStatus
  }

  /**
   * Looks up and returns the local cluster command connection.
   */
  private def clusterCommandDaemon = system.actorFor(RootActorPath(selfAddress) / "system" / "cluster" / "commands")

  /**
   * Looks up and returns the remote cluster command connection for the specific address.
   */
  private def clusterCommandConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "commands")

  /**
   * Looks up and returns the remote cluster gossip connection for the specific address.
   */
  private def clusterGossipConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "gossip")

  /**
   * Gets the addresses of a all the 'deputy' nodes - excluding this node if part of the group.
   */
  private def deputyNodes(addresses: IndexedSeq[Address]): IndexedSeq[Address] =
    addresses drop 1 take NrOfDeputyNodes filterNot (_ == selfAddress)

  /**
   * INTERNAL API
   */
  private[akka] def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None
    else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

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
          { if (unreachable.nonEmpty) "\nUnreachable:\n\t" + unreachable.mkString("\n\t") else "" } +
          { if (metaData.nonEmpty) "\nMeta Data:\t" + metaData.toString else "" }
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
    log.info("Cluster Node [{}] - registering cluster JMX MBean [{}]", selfAddress, clusterMBeanName)
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
    } catch {
      case e: InstanceAlreadyExistsException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }
}
