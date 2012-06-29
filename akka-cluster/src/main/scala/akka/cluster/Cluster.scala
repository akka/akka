/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import akka.actor.Status._
import akka.ConfigurationException
import akka.dispatch.Await
import akka.dispatch.MonitorableThreadFactory
import akka.event.Logging
import akka.jsr166y.ThreadLocalRandom
import akka.pattern._
import akka.remote._
import akka.routing._
import akka.util._
import akka.util.duration._
import akka.util.internal.HashedWheelTimer
import com.google.protobuf.ByteString
import java.io.Closeable
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.{ AtomicReference, AtomicBoolean }
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit._
import javax.management._
import MemberStatus._
import scala.annotation.tailrec
import scala.collection.immutable.{ Map, SortedSet }
import scala.collection.GenTraversableOnce
import java.util.concurrent.atomic.AtomicLong

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
object ClusterUserAction {

  /**
   * Command to join the cluster. Sent when a node (represented by 'address')
   * wants to join another node (the receiver).
   */
  case class Join(address: Address) extends ClusterMessage

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
   * Command to leave the cluster.
   */
  case class Leave(address: Address) extends ClusterMessage

  /**
   * Command to mark node as temporary down.
   */
  case class Down(address: Address) extends ClusterMessage
}

/**
 * Cluster commands sent by the LEADER.
 */
object ClusterLeaderAction {

  /**
   * INTERNAL API.
   *
   * Command to mark a node to be removed from the cluster immediately.
   * Can only be sent by the leader.
   */
  private[cluster] case class Exit(address: Address) extends ClusterMessage

  /**
   * INTERNAL API.
   *
   * Command to remove a node from the cluster immediately.
   */
  private[cluster] case class Remove(address: Address) extends ClusterMessage
}

/**
 * Represents the address and the current status of a cluster member node.
 *
 * Note: `hashCode` and `equals` are solely based on the underlying `Address`, not its `MemberStatus`.
 */
class Member(val address: Address, val status: MemberStatus) extends ClusterMessage {
  override def hashCode = address.##
  override def equals(other: Any) = Member.unapply(this) == Member.unapply(other)
  override def toString = "Member(address = %s, status = %s)" format (address, status)
  def copy(address: Address = this.address, status: MemberStatus = this.status): Member = new Member(address, status)
}

/**
 * Module with factory and ordering methods for Member instances.
 */
object Member {

  /**
   * `Address` ordering type class, sorts addresses by host and port.
   */
  implicit val addressOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
    if (a.host != b.host) a.host.getOrElse("").compareTo(b.host.getOrElse("")) < 0
    else if (a.port != b.port) a.port.getOrElse(0) < b.port.getOrElse(0)
    else false
  }

  /**
   * `Member` ordering type class, sorts members by host and port with the exception that
   * it puts all members that are in MemberStatus.EXITING last.
   */
  implicit val ordering: Ordering[Member] = Ordering.fromLessThan[Member] { (a, b) ⇒
    if (a.status == Exiting && b.status != Exiting) false
    else if (a.status != Exiting && b.status == Exiting) true
    else addressOrdering.compare(a.address, b.address) < 0
  }

  def apply(address: Address, status: MemberStatus): Member = new Member(address, status)

  def unapply(other: Any) = other match {
    case m: Member ⇒ Some(m.address)
    case _         ⇒ None
  }

  def pickHighestPriority(a: Set[Member], b: Set[Member]): Set[Member] = {
    // group all members by Address => Seq[Member]
    val groupedByAddress = (a.toSeq ++ b.toSeq).groupBy(_.address)
    // pick highest MemberStatus
    (Set.empty[Member] /: groupedByAddress) {
      case (acc, (_, members)) ⇒ acc + members.reduceLeft(highestPriorityOf)
    }
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
    case (Up, Joining)      ⇒ m2
    case (Joining, Up)      ⇒ m1
    case (Joining, Joining) ⇒ m1
    case (Up, Up)           ⇒ m1
  }

  // FIXME Workaround for https://issues.scala-lang.org/browse/SI-5986
  // SortedSet + and ++ operators replaces existing element
  // Use these :+ and :++ operators for the Gossip members
  implicit def sortedSetWorkaround(sortedSet: SortedSet[Member]): SortedSetWorkaround = new SortedSetWorkaround(sortedSet)
  class SortedSetWorkaround(sortedSet: SortedSet[Member]) {
    implicit def :+(elem: Member): SortedSet[Member] = {
      if (sortedSet.contains(elem)) sortedSet
      else sortedSet + elem
    }

    implicit def :++(elems: GenTraversableOnce[Member]): SortedSet[Member] =
      sortedSet ++ (elems.toSet diff sortedSet)
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
   * Using the same notion for 'unavailable' as 'non-convergence': DOWN
   */
  def isUnavailable: Boolean = this == Down
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
  seen: Map[Address, VectorClock] = Map.empty,
  unreachable: Set[Member] = Set.empty) {

  def isNonDownUnreachable(address: Address): Boolean =
    unreachable.exists { m ⇒ m.address == address && m.status != Down }

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
 * When a node is joining the `Member`, with status `Joining`, is added to `members`.
 * If the joining node was downed it is moved from `overview.unreachable` (status `Down`)
 * to `members` (status `Joining`). It cannot rejoin if not first downed.
 *
 * When convergence is reached the leader change status of `members` from `Joining`
 * to `Up`.
 *
 * When failure detector consider a node as unavailable it will be moved from
 * `members` to `overview.unreachable`.
 *
 * When a node is downed, either manually or automatically, its status is changed to `Down`.
 * It is also removed from `overview.seen` table. The node will reside as `Down` in the
 * `overview.unreachable` set until joining again and it will then go through the normal
 * joining procedure.
 *
 * When a `Gossip` is received the version (vector clock) is used to determine if the
 * received `Gossip` is newer or older than the current local `Gossip`. The received `Gossip`
 * and local `Gossip` is merged in case of conflicting version, i.e. vector clocks without
 * same history. When merged the seen table is cleared.
 *
 * When a node is told by the user to leave the cluster the leader will move it to `Leaving`
 * and then rebalance and repartition the cluster and start hand-off by migrating the actors
 * from the leaving node to the new partitions. Once this process is complete the leader will
 * move the node to the `Exiting` state and once a convergence is complete move the node to
 * `Removed` by removing it from the `members` set and sending a `Removed` command to the
 * removed node telling it to shut itself down.
 */
case class Gossip(
  overview: GossipOverview = GossipOverview(),
  members: SortedSet[Member], // sorted set of members with their status, sorted by address
  meta: Map[String, Array[Byte]] = Map.empty,
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
  def :+(node: VectorClock.Node): Gossip = copy(version = version :+ node)

  /**
   * Adds a member to the member node ring.
   */
  def :+(member: Member): Gossip = {
    if (members contains member) this
    else this copy (members = members :+ member)
  }

  /**
   * Marks the gossip as seen by this node (address) by updating the address entry in the 'gossip.overview.seen'
   * Map with the VectorClock (version) for the new gossip.
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

    // 3. merge unreachable by selecting the single Member with highest MemberStatus out of the Member groups
    val mergedUnreachable = Member.pickHighestPriority(this.overview.unreachable, that.overview.unreachable)

    // 4. merge members by selecting the single Member with highest MemberStatus out of the Member groups,
    //    and exclude unreachable
    val mergedMembers = Gossip.emptyMembers :++ Member.pickHighestPriority(this.members, that.members).filterNot(mergedUnreachable.contains)

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
 * INTERNAL API.
 *
 * Manages routing of the different cluster commands.
 * Instantiated as a single instance for each Cluster - e.g. commands are serialized
 * to Cluster message after message, but concurrent with other types of messages.
 */
private[cluster] final class ClusterCommandDaemon(cluster: Cluster) extends Actor {
  import ClusterUserAction._
  import ClusterLeaderAction._

  val log = Logging(context.system, this)

  def receive = {
    case JoinSeedNode                    ⇒ joinSeedNode()
    case InitJoin                        ⇒ sender ! InitJoinAck(cluster.selfAddress)
    case InitJoinAck(address)            ⇒ cluster.join(address)
    case Join(address)                   ⇒ cluster.joining(address)
    case Down(address)                   ⇒ cluster.downing(address)
    case Leave(address)                  ⇒ cluster.leaving(address)
    case Exit(address)                   ⇒ cluster.exiting(address)
    case Remove(address)                 ⇒ cluster.removing(address)
    case Failure(e: AskTimeoutException) ⇒ joinSeedNodeTimeout()
  }

  def joinSeedNode(): Unit = {
    val seedRoutees = for (address ← cluster.seedNodes; if address != cluster.selfAddress)
      yield self.path.toStringWithAddress(address)
    if (seedRoutees.isEmpty) {
      cluster join cluster.selfAddress
    } else {
      implicit val within = Timeout(cluster.settings.SeedNodeTimeout)
      val seedRouter = context.actorOf(
        Props.empty.withRouter(ScatterGatherFirstCompletedRouter(
          routees = seedRoutees, within = within.duration)))
      seedRouter ? InitJoin pipeTo self
      seedRouter ! PoisonPill
    }
  }

  def joinSeedNodeTimeout(): Unit = cluster join cluster.selfAddress

  override def unhandled(unknown: Any) = log.error("Illegal command [{}]", unknown)
}

/**
 * INTERNAL API.
 *
 * Receives Gossip messages and delegates to Cluster.
 * Instantiated as a single instance for each Cluster - e.g. gossips are serialized
 * to Cluster message after message, but concurrent with other types of messages.
 */
private[cluster] final class ClusterGossipDaemon(cluster: Cluster) extends Actor with ActorLogging {

  def receive = {
    case GossipEnvelope(from, gossip) ⇒ cluster.receiveGossip(from, gossip)
  }

  override def unhandled(unknown: Any) = log.error("[{}] can not respond to messages - received [{}]",
    self.path, unknown)
}

/**
 * INTERNAL API.
 *
 * Receives Heartbeat messages and delegates to Cluster.
 * Instantiated as a single instance for each Cluster - e.g. heartbeats are serialized
 * to Cluster message after message, but concurrent with other types of messages.
 */
private[cluster] final class ClusterHeartbeatDaemon(cluster: Cluster) extends Actor with ActorLogging {

  def receive = {
    case Heartbeat(from) ⇒ cluster.receiveHeartbeat(from)
  }

  override def unhandled(unknown: Any) = log.error("[{}] can not respond to messages - received [{}]",
    self.path, unknown)
}

/**
 * INTERNAL API.
 *
 * Supervisor managing the different Cluster daemons.
 */
private[cluster] final class ClusterDaemonSupervisor(cluster: Cluster) extends Actor with ActorLogging {

  val configuredDispatcher = cluster.settings.UseDispatcher
  private val commands = context.actorOf(Props(new ClusterCommandDaemon(cluster)).
    withDispatcher(configuredDispatcher), name = "commands")
  private val gossip = context.actorOf(Props(new ClusterGossipDaemon(cluster)).
    withDispatcher(configuredDispatcher).
    withRouter(RoundRobinRouter(cluster.settings.NrOfGossipDaemons)),
    name = "gossip")
  private val heartbeat = context.actorOf(Props(new ClusterHeartbeatDaemon(cluster)).
    withDispatcher(configuredDispatcher), name = "heartbeat")

  def receive = Actor.emptyBehavior

  override def unhandled(unknown: Any): Unit = log.error("[{}] can not respond to messages - received [{}]",
    self.path, unknown)
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

    val failureDetector = {
      import clusterSettings.{ FailureDetectorImplementationClass ⇒ fqcn }
      system.dynamicAccess.createInstanceFor[FailureDetector](
        fqcn, Seq(classOf[ActorSystem] -> system, classOf[ClusterSettings] -> clusterSettings)).fold(
          e ⇒ throw new ConfigurationException("Could not create custom failure detector [" + fqcn + "] due to:" + e.toString),
          identity)
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
  def isRunning: Boolean

  def join(address: String)
  def leave(address: String)
  def down(address: String)
}

/**
 * This module is responsible for Gossiping cluster information. The abstraction maintains the list of live
 * and dead members. Periodically i.e. every 1 second this module chooses a random member and initiates a round
 * of Gossip with it.
 * <p/>
 * During each of these runs the member initiates gossip exchange according to following rules:
 * <pre>
 *   1) Gossip to random live member (if any)
 *   2) If the member gossiped to at (1) was not deputy, or the number of live members is less than number of deputy list,
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
    joinInProgress: Map[Address, Deadline] = Map.empty,
    memberMembershipChangeListeners: Set[MembershipChangeListener] = Set.empty)

  if (!system.provider.isInstanceOf[RemoteActorRefProvider])
    throw new ConfigurationException("ActorSystem[" + system + "] needs to have a 'RemoteActorRefProvider' enabled in the configuration")

  private val remote: RemoteActorRefProvider = system.provider.asInstanceOf[RemoteActorRefProvider]

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val settings = new ClusterSettings(system.settings.config, system.name)
  import settings._

  val selfAddress = remote.transport.address
  private val selfHeartbeat = Heartbeat(selfAddress)

  private val vclockNode = VectorClock.Node(selfAddress.toString)

  implicit private val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val serialization = remote.serialization

  private val _isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Node")

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")

  log.info("Cluster Node [{}] - is starting up...", selfAddress)

  // create supervisor for daemons under path "/system/cluster"
  private val clusterDaemons = {
    val createChild = CreateChild(Props(new ClusterDaemonSupervisor(this)).
      withDispatcher(UseDispatcher), name = "cluster")
    Await.result(system.systemGuardian ? createChild, defaultTimeout.duration) match {
      case a: ActorRef  ⇒ a
      case e: Exception ⇒ throw e
    }
  }

  private def createCleanState: State = {
    // note that self is not initially member,
    // and the Gossip is not versioned for this 'Node' yet
    State(Gossip(members = Gossip.emptyMembers))
  }

  private val state = new AtomicReference[State](createCleanState)

  // try to join one of the nodes defined in the 'akka.cluster.seed-nodes'
  if (AutoJoin) joinSeedNode()

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
  private val gossipTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(GossipInterval), GossipInterval) {
      gossip()
    }

  // start periodic heartbeat to all nodes in cluster
  private val heartbeatTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(HeartbeatInterval), HeartbeatInterval) {
      heartbeat()
    }

  // start periodic cluster failure detector reaping (moving nodes condemned by the failure detector to unreachable list)
  private val failureDetectorReaperTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(UnreachableNodesReaperInterval), UnreachableNodesReaperInterval) {
      reapUnreachableMembers()
    }

  // start periodic leader action management (only applies for the current leader)
  private val leaderActionsTask =
    FixedRateTask(clusterScheduler, PeriodicTasksInitialDelay.max(LeaderActionsInterval), LeaderActionsInterval) {
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
   * Returns true if the cluster node is up and running, false if it is shut down.
   */
  def isRunning: Boolean = _isRunning.get

  /**
   * Latest gossip.
   */
  def latestGossip: Gossip = state.get.latestGossip

  /**
   * Member status for this node (`MemberStatus`).
   *
   * NOTE: If the node has been removed from the cluster (and shut down) then it's status is set to the 'REMOVED' tombstone state
   *       and is no longer present in the node ring or any other part of the gossiping state. However in order to maintain the
   *       model and the semantics the user would expect, this method will in this situation return `MemberStatus.Removed`.
   */
  def status: MemberStatus = {
    if (isRunning) self.status
    else MemberStatus.Removed
  }

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
   * Make it possible to override/configure seedNodes from tests without
   * specifying in config. Addresses are unknown before startup time.
   */
  def seedNodes: IndexedSeq[Address] = SeedNodes

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
  @tailrec
  final def join(address: Address): Unit = {
    val localState = state.get
    // wipe our state since a node that joins a cluster must be empty
    val newState = createCleanState copy (joinInProgress = Map.empty + (address -> (Deadline.now + JoinTimeout)),
      memberMembershipChangeListeners = localState.memberMembershipChangeListeners)
    // wipe the failure detector since we are starting fresh and shouldn't care about the past
    failureDetector.reset()
    if (!state.compareAndSet(localState, newState)) join(address) // recur
    else {
      val connection = clusterCommandConnectionFor(address)
      val command = ClusterUserAction.Join(selfAddress)
      log.info("Cluster Node [{}] - Trying to send JOIN to [{}] through connection [{}]", selfAddress, address, connection)
      connection ! command
    }
  }

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   */
  def leave(address: Address): Unit = {
    clusterCommandDaemon ! ClusterUserAction.Leave(address)
  }

  /**
   * Send command to DOWN the node specified by 'address'.
   */
  def down(address: Address): Unit = {
    clusterCommandDaemon ! ClusterUserAction.Down(address)
  }

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * INTERNAL API.
   *
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   *
   * Should not called by the user. The user can issue a LEAVE command which will tell the node
   * to go through graceful handoff process `LEAVE -> EXITING -> REMOVED -> SHUTDOWN`.
   */
  private[cluster] def shutdown(): Unit = {
    if (_isRunning.compareAndSet(true, false)) {
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
      log.info("Cluster Node [{}] - Cluster node successfully shut down", selfAddress)
    }
  }

  /**
   * INTERNAL API.
   *
   * State transition to JOINING - new node joining.
   */
  @tailrec
  private[cluster] final def joining(node: Address): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip
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
      val newMembers = localMembers :+ Member(node, Joining) :+ Member(selfAddress, Joining)
      val newGossip = localGossip copy (overview = newOverview, members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      val newState = localState copy (latestGossip = seenVersionedGossip)

      if (!state.compareAndSet(localState, newState)) joining(node) // recur if we failed update
      else {
        log.info("Cluster Node [{}] - Node [{}] is JOINING", selfAddress, node)
        // treat join as initial heartbeat, so that it becomes unavailable if nothing more happens
        if (node != selfAddress) {
          failureDetector heartbeat node
          gossipTo(node)
        }
        notifyMembershipChangeListeners(localState, newState)
      }
    }
  }

  /**
   * INTERNAL API.
   *
   * State transition to LEAVING.
   */
  @tailrec
  private[cluster] final def leaving(address: Address) {
    val localState = state.get
    val localGossip = localState.latestGossip
    if (localGossip.members.exists(_.address == address)) { // only try to update if the node is available (in the member ring)
      val newMembers = localGossip.members map { member ⇒ if (member.address == address) Member(address, Leaving) else member } // mark node as LEAVING
      val newGossip = localGossip copy (members = newMembers)

      val versionedGossip = newGossip :+ vclockNode
      val seenVersionedGossip = versionedGossip seen selfAddress

      val newState = localState copy (latestGossip = seenVersionedGossip)

      if (!state.compareAndSet(localState, newState)) leaving(address) // recur if we failed update
      else {
        log.info("Cluster Node [{}] - Marked address [{}] as LEAVING", selfAddress, address)
        notifyMembershipChangeListeners(localState, newState)
      }
    }
  }

  /**
   * INTERNAL API.
   *
   * State transition to EXITING.
   */
  private[cluster] final def exiting(address: Address): Unit = {
    log.info("Cluster Node [{}] - Marked node [{}] as EXITING", selfAddress, address)
    // FIXME implement when we implement hand-off
  }

  /**
   * INTERNAL API.
   *
   * State transition to REMOVED.
   *
   * This method is for now only called after the LEADER have sent a Removed message - telling the node
   * to shut down himself.
   *
   * In the future we might change this to allow the USER to send a Removed(address) message telling an
   * arbitrary node to be moved direcly from UP -> REMOVED.
   */
  private[cluster] final def removing(address: Address): Unit = {
    log.info("Cluster Node [{}] - Node has been REMOVED by the leader - shutting down...", selfAddress)
    shutdown()
  }

  /**
   * INTERNAL API.
   *
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
    val versionedGossip = newGossip :+ vclockNode
    val newState = localState copy (latestGossip = versionedGossip seen selfAddress)

    if (!state.compareAndSet(localState, newState)) downing(address) // recur if we fail the update
    else {
      notifyMembershipChangeListeners(localState, newState)
    }
  }

  // Can be removed when gossip has been optimized
  private val _receivedGossipCount = new AtomicLong
  /**
   * INTERNAL API.
   */
  private[cluster] def receivedGossipCount: Long = _receivedGossipCount.get

  /**
   * INTERNAL API.
   *
   * Receive new gossip.
   */
  @tailrec
  final private[cluster] def receiveGossip(from: Address, remoteGossip: Gossip): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip

    if (!localGossip.overview.isNonDownUnreachable(from)) {

      val winningGossip =
        if (remoteGossip.version <> localGossip.version) {
          // concurrent
          val mergedGossip = remoteGossip merge localGossip
          val versionedMergedGossip = mergedGossip :+ vclockNode

          versionedMergedGossip

        } else if (remoteGossip.version < localGossip.version) {
          // local gossip is newer
          localGossip

        } else {
          // remote gossip is newer
          remoteGossip
        }

      val newJoinInProgress =
        if (localState.joinInProgress.isEmpty) localState.joinInProgress
        else localState.joinInProgress --
          winningGossip.members.map(_.address) --
          winningGossip.overview.unreachable.map(_.address)

      val newState = localState copy (
        latestGossip = winningGossip seen selfAddress,
        joinInProgress = newJoinInProgress)

      // for all new joining nodes we optimistically remove them from the failure detector, since if we wait until
      // we have won the CAS, then the node might be picked up by the reapUnreachableMembers task and moved to
      // unreachable before we can remove the node from the failure detector
      (newState.latestGossip.members -- localState.latestGossip.members).filter(_.status == Joining).foreach {
        case node ⇒ failureDetector.remove(node.address)
      }

      // if we won the race then update else try again
      if (!state.compareAndSet(localState, newState)) receiveGossip(from, remoteGossip) // recur if we fail the update
      else {
        log.debug("Cluster Node [{}] - Receiving gossip from [{}]", selfAddress, from)

        if ((winningGossip ne localGossip) && (winningGossip ne remoteGossip))
          log.debug(
            """Couldn't establish a causal relationship between "remote" gossip and "local" gossip - Remote[{}] - Local[{}] - merged them into [{}]""",
            remoteGossip, localGossip, winningGossip)

        _receivedGossipCount.incrementAndGet()
        notifyMembershipChangeListeners(localState, newState)

        if ((winningGossip ne remoteGossip) || (newState.latestGossip ne remoteGossip)) {
          // send back gossip to sender when sender had different view, i.e. merge, or sender had
          // older or sender had newer
          gossipTo(from)
        }
      }
    }
  }

  /**
   * INTERNAL API.
   */
  private[cluster] def receiveHeartbeat(from: Address): Unit = failureDetector heartbeat from

  /**
   * Joins the pre-configured contact points.
   */
  private def joinSeedNode(): Unit = clusterCommandDaemon ! ClusterUserAction.JoinSeedNode

  /**
   * INTERNAL API.
   *
   * Gossips latest gossip to an address.
   */
  private[cluster] def gossipTo(address: Address): Unit = {
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
   * INTERNAL API.
   */
  private[cluster] def gossipToDeputyProbablity(membersSize: Int, unreachableSize: Int, nrOfDeputyNodes: Int): Double = {
    if (nrOfDeputyNodes > membersSize) 1.0
    else if (nrOfDeputyNodes == 0) 0.0
    else (membersSize + unreachableSize) match {
      case 0   ⇒ 0.0
      case sum ⇒ (nrOfDeputyNodes + unreachableSize).toDouble / sum
    }
  }

  /**
   * INTERNAL API.
   *
   * Initates a new round of gossip.
   */
  private[cluster] def gossip(): Unit = {
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

      // 1. gossip to a random alive member with preference to a member
      // with older or newer gossip version
      val nodesWithdifferentView = {
        val localMemberAddressesSet = localGossip.members map { _.address }
        for {
          (address, version) ← localGossip.overview.seen
          if localMemberAddressesSet contains address
          if version != localGossip.version
        } yield address
      }
      val gossipedToAlive =
        if (nodesWithdifferentView.nonEmpty && ThreadLocalRandom.current.nextDouble() < GossipDifferentViewProbability)
          gossipToRandomNodeOf(nodesWithdifferentView.toIndexedSeq)
        else
          gossipToRandomNodeOf(localMemberAddresses)

      // 2. gossip to a deputy nodes for facilitating partition healing
      val deputies = deputyNodes(localMemberAddresses)
      val alreadyGossipedToDeputy = gossipedToAlive.map(deputies.contains(_)).getOrElse(false)
      if ((!alreadyGossipedToDeputy || localMembersSize < seedNodes.size) && deputies.nonEmpty) {
        val probability = gossipToDeputyProbablity(localMembersSize, localUnreachableSize, seedNodes.size)
        if (ThreadLocalRandom.current.nextDouble() < probability)
          gossipToRandomNodeOf(deputies)
      }
    }
  }

  /**
   * INTERNAL API.
   */
  private[cluster] def heartbeat(): Unit = {
    removeOverdueJoinInProgress()
    val localState = state.get

    val beatTo = localState.latestGossip.members.toSeq.map(_.address) ++ localState.joinInProgress.keys

    for (address ← beatTo; if address != selfAddress) {
      val connection = clusterHeartbeatConnectionFor(address)
      log.debug("Cluster Node [{}] - Heartbeat to [{}]", selfAddress, connection)
      connection ! selfHeartbeat
    }
  }

  /**
   * INTERNAL API.
   *
   * Reaps the unreachable members (moves them to the 'unreachable' list in the cluster overview) according to the failure detector's verdict.
   */
  @tailrec
  final private[cluster] def reapUnreachableMembers(): Unit = {
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
        val versionedGossip = newGossip :+ vclockNode
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
   * INTERNAL API.
   *
   * Removes overdue joinInProgress from State.
   */
  @tailrec
  final private[cluster] def removeOverdueJoinInProgress(): Unit = {
    val localState = state.get
    val overdueJoins = localState.joinInProgress collect {
      case (address, deadline) if deadline.isOverdue ⇒ address
    }
    if (overdueJoins.nonEmpty) {
      val newState = localState copy (joinInProgress = localState.joinInProgress -- overdueJoins)
      if (!state.compareAndSet(localState, newState)) removeOverdueJoinInProgress() // recur
    }
  }

  /**
   * INTERNAL API.
   *
   * Runs periodic leader actions, such as auto-downing unreachable nodes, assigning partitions etc.
   */
  @tailrec
  final private[cluster] def leaderActions(): Unit = {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val isLeader = localMembers.nonEmpty && (selfAddress == localMembers.head.address)

    if (isLeader && isAvailable(localState)) {
      // only run the leader actions if we are the LEADER and available

      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localUnreachableMembers = localOverview.unreachable
      val hasPartionHandoffCompletedSuccessfully: Boolean = {
        // FIXME implement partion handoff and a check if it is completed - now just returns TRUE - e.g. has completed successfully
        true
      }

      // Leader actions are as follows:
      //   1. Move EXITING     => REMOVED     -- When all nodes have seen that the node is EXITING (convergence) - remove the nodes from the node ring and seen table
      //   2. Move JOINING     => UP          -- When a node joins the cluster
      //   3. Move LEAVING     => EXITING     -- When all partition handoff has completed
      //   4. Move UNREACHABLE => DOWN        -- When the node is in the UNREACHABLE set it can be auto-down by leader
      //   5. Store away all stuff needed for the side-effecting processing in 10.
      //   6. Updating the vclock version for the changes
      //   7. Updating the 'seen' table
      //   8. Try to update the state with the new gossip
      //   9. If failure - retry
      //  10. If success - run all the side-effecting processing

      val (
        newGossip: Gossip,
        hasChangedState: Boolean,
        upMembers,
        exitingMembers,
        removedMembers,
        unreachableButNotDownedMembers) =

        if (convergence(localGossip).isDefined) {
          // we have convergence - so we can't have unreachable nodes

          // transform the node member ring - filterNot/map/map
          val newMembers =
            localMembers filterNot { member ⇒
              // ----------------------
              // 1. Move EXITING => REMOVED - e.g. remove the nodes from the 'members' set/node ring and seen table
              // ----------------------
              member.status == MemberStatus.Exiting

            } map { member ⇒
              // ----------------------
              // 2. Move JOINING => UP (once all nodes have seen that this node is JOINING e.g. we have a convergence)
              // ----------------------
              if (member.status == Joining) member copy (status = Up)
              else member

            } map { member ⇒
              // ----------------------
              // 3. Move LEAVING => EXITING (once we have a convergence on LEAVING *and* if we have a successful partition handoff)
              // ----------------------
              if (member.status == Leaving && hasPartionHandoffCompletedSuccessfully) member copy (status = Exiting)
              else member
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

          val (exitingMembers, newMembers3) = newMembers2 partition (_.status == Leaving && hasPartionHandoffCompletedSuccessfully)

          val hasChangedState = removedMembers.nonEmpty || upMembers.nonEmpty || exitingMembers.nonEmpty

          // removing REMOVED nodes from the 'seen' table
          val newSeen = localSeen -- removedMembers.map(_.address)

          // removing REMOVED nodes from the 'unreachable' set
          val newUnreachableMembers = localUnreachableMembers -- removedMembers

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (members = newMembers, overview = newOverview) // update gossip

          (newGossip, hasChangedState, upMembers, exitingMembers, removedMembers, Set.empty[Member])

        } else if (AutoDown) {
          // we don't have convergence - so we might have unreachable nodes

          // if 'auto-down' is turned on, then try to auto-down any unreachable nodes
          val newUnreachableMembers = localUnreachableMembers.map { member ⇒
            // ----------------------
            // 5. Move UNREACHABLE => DOWN (auto-downing by leader)
            // ----------------------
            if (member.status == Down) member // no need to DOWN members already DOWN
            else member copy (status = Down)
          }

          // Check for the need to do side-effecting on successful state change
          val (unreachableButNotDownedMembers, _) = localUnreachableMembers partition (_.status != Down)

          // removing nodes marked as DOWN from the 'seen' table
          val newSeen = localSeen -- newUnreachableMembers.collect { case m if m.status == Down ⇒ m.address }

          val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableMembers) // update gossip overview
          val newGossip = localGossip copy (overview = newOverview) // update gossip

          (newGossip, unreachableButNotDownedMembers.nonEmpty, Set.empty[Member], Set.empty[Member], Set.empty[Member], unreachableButNotDownedMembers)

        } else (localGossip, false, Set.empty[Member], Set.empty[Member], Set.empty[Member], Set.empty[Member])

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

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // ----------------------
        // 8. Try to update the state with the new gossip
        // ----------------------
        if (!state.compareAndSet(localState, newState)) {

          // ----------------------
          // 9. Failure - retry
          // ----------------------
          leaderActions() // recur

        } else {
          // ----------------------
          // 10. Success - run all the side-effecting processing
          // ----------------------

          // log the move of members from joining to up
          upMembers foreach { member ⇒ log.info("Cluster Node [{}] - Leader is moving node [{}] from JOINING to UP", selfAddress, member.address) }

          //  tell all removed members to remove and shut down themselves
          removedMembers foreach { member ⇒
            val address = member.address
            log.info("Cluster Node [{}] - Leader is moving node [{}] from EXITING to REMOVED - and removing node from node ring", selfAddress, address)
            clusterCommandConnectionFor(address) ! ClusterLeaderAction.Remove(address)
          }

          //  tell all exiting members to exit
          exitingMembers foreach { member ⇒
            val address = member.address
            log.info("Cluster Node [{}] - Leader is moving node [{}] from LEAVING to EXITING", selfAddress, address)
            clusterCommandConnectionFor(address) ! ClusterLeaderAction.Exit(address) // FIXME should use ? to await completion of handoff?
          }

          // log the auto-downing of the unreachable nodes
          unreachableButNotDownedMembers foreach { member ⇒
            log.info("Cluster Node [{}] - Leader is marking unreachable node [{}] as DOWN", selfAddress, member.address)
          }

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
    val seen = overview.seen

    // First check that:
    //   1. we don't have any members that are unreachable, or
    //   2. all unreachable members in the set have status DOWN
    // Else we can't continue to check for convergence
    // When that is done we check that all the entries in the 'seen' table have the same vector clock version
    // and that all members exists in seen table
    val hasUnreachable = unreachable.nonEmpty && unreachable.exists { _.status != Down }
    val allMembersInSeen = gossip.members.forall(m ⇒ seen.contains(m.address))

    if (hasUnreachable) {
      log.debug("Cluster Node [{}] - No cluster convergence, due to unreachable nodes [{}].", selfAddress, unreachable)
      None
    } else if (!allMembersInSeen) {
      log.debug("Cluster Node [{}] - No cluster convergence, due to members not in seen table [{}].", selfAddress,
        gossip.members.map(_.address) -- seen.keySet)
      None
    } else {

      val views = seen.values.toSet.size

      if (views == 1) {
        log.debug("Cluster Node [{}] - Cluster convergence reached: [{}]", selfAddress, gossip.members.mkString(", "))
        Some(gossip)
      } else {
        log.debug("Cluster Node [{}] - No cluster convergence, since not all nodes have seen the same state yet. [{} of {}]",
          selfAddress, views, seen.values.size)
        None
      }
    }
  }

  private def isAvailable(state: State): Boolean = !isUnavailable(state)

  private def isUnavailable(state: State): Boolean = {
    val localGossip = state.latestGossip
    val isUnreachable = localGossip.overview.unreachable exists { _.address == selfAddress }
    val hasUnavailableMemberStatus = localGossip.members exists { m ⇒ (m == self) && m.status.isUnavailable }
    isUnreachable || hasUnavailableMemberStatus
  }

  private def notifyMembershipChangeListeners(oldState: State, newState: State): Unit = {
    val oldMembersStatus = oldState.latestGossip.members.map(m ⇒ (m.address, m.status))
    val newMembersStatus = newState.latestGossip.members.map(m ⇒ (m.address, m.status))
    if (newMembersStatus != oldMembersStatus)
      newState.memberMembershipChangeListeners foreach { _ notify newState.latestGossip.members }
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
   * Looks up and returns the remote cluster heartbeat connection for the specific address.
   */
  private def clusterHeartbeatConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "heartbeat")

  /**
   * Gets the addresses of a all the 'deputy' nodes - excluding this node if part of the group.
   */
  private def deputyNodes(addresses: IndexedSeq[Address]): IndexedSeq[Address] =
    addresses filterNot (_ == selfAddress) intersect seedNodes

  /**
   * INTERNAL API.
   */
  private[cluster] def selectRandomNode(addresses: IndexedSeq[Address]): Option[Address] =
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

      def isRunning: Boolean = clusterNode.isRunning

      // JMX commands

      def join(address: String) = clusterNode.join(AddressFromURIString(address))

      def leave(address: String) = clusterNode.leave(AddressFromURIString(address))

      def down(address: String) = clusterNode.down(AddressFromURIString(address))
    }
    log.info("Cluster Node [{}] - registering cluster JMX MBean [{}]", selfAddress, clusterMBeanName)
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
    } catch {
      case e: InstanceAlreadyExistsException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }
}
