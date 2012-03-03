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
trait MetaDataChangeListener { // FIXME add management and notification for MetaDataChangeListener
  def notify(meta: Map[String, Array[Byte]]): Unit
}

// FIXME create Protobuf messages out of all the Gossip stuff - but wait until the prototol is fully stablized.

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
case class Member(address: Address, status: MemberStatus) extends ClusterMessage

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
}

/**
 * Represents the overview of the cluster, holds the cluster convergence table and set with unreachable nodes.
 */
case class GossipOverview(
  seen: Map[Address, VectorClock] = Map.empty[Address, VectorClock],
  unreachable: Set[Address] = Set.empty[Address]) {

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

  override def toString =
    "Gossip(" +
      "overview = " + overview +
      ", members = [" + members.mkString(", ") +
      "], meta = [" + meta.mkString(", ") +
      "], version = " + version +
      ")"
}

/**
 * FSM actor managing the different cluster nodes states.
 * Instantiated as a single instance for each Node - e.g. commands are serialized to Node message after message.
 */
final class ClusterCommandDaemon extends Actor with FSM[MemberStatus, Unit] {
  val node = Node(context.system)

  // ========================
  // === START IN JOINING ==
  startWith(MemberStatus.Joining, Unit)

  // ========================
  // === WHEN JOINING ===
  when(MemberStatus.Joining) {
    case Event(ClusterAction.Up(address), _) ⇒
      node.up(address)
      goto(MemberStatus.Up)

    case Event(ClusterAction.Remove(address), _) ⇒
      node.removing(address)
      goto(MemberStatus.Removed)

    case Event(ClusterAction.Down(address), _) ⇒
      node.downing(address)
      goto(MemberStatus.Down)
  }

  // ========================
  // === WHEN UP ===
  when(MemberStatus.Up) {
    case Event(ClusterAction.Down(address), _) ⇒
      node.downing(address)
      goto(MemberStatus.Down)

    case Event(ClusterAction.Leave(address), _) ⇒
      node.leaving(address)
      goto(MemberStatus.Leaving)

    case Event(ClusterAction.Exit(address), _) ⇒
      node.exiting(address)
      goto(MemberStatus.Exiting)

    case Event(ClusterAction.Remove(address), _) ⇒
      node.removing(address)
      goto(MemberStatus.Removed)
  }

  // ========================
  // === WHEN LEAVING ===
  when(MemberStatus.Leaving) {
    case Event(ClusterAction.Down(address), _) ⇒
      node.downing(address)
      goto(MemberStatus.Down)

    case Event(ClusterAction.Remove(address), _) ⇒
      node.removing(address)
      goto(MemberStatus.Removed)
  }

  // ========================
  // === WHEN EXITING ===
  when(MemberStatus.Exiting) {
    case Event(ClusterAction.Remove(address), _) ⇒
      node.removing(address)
      goto(MemberStatus.Removed)
  }

  // ========================
  // === WHEN DOWN ===
  when(MemberStatus.Down) {
    // FIXME How to transition from DOWN => JOINING when node comes back online. Can't just listen to Gossip message since it is received be another actor. How to fix this?
    case Event(ClusterAction.Remove(address), _) ⇒
      node.removing(address)
      goto(MemberStatus.Removed)
  }

  // ========================
  // === WHEN REMOVED ===
  when(MemberStatus.Removed) {
    case command ⇒
      log.warning("Removed node [{}] received cluster command [{}]", context.system.name, command)
      stay
  }

  // ========================
  // === GENERIC AND UNHANDLED COMMANDS ===
  whenUnhandled {
    // should be able to handle Join in any state
    case Event(ClusterAction.Join(address), _) ⇒
      node.joining(address)
      stay

    case Event(illegal, _) ⇒ {
      log.error("Illegal command [{}] in state [{}]", illegal, stateName)
      stay
    }
  }
}

/**
 * Pooled and routed with N number of configurable instances.
 * Concurrent access to Node.
 */
final class ClusterGossipDaemon extends Actor {
  val log = Logging(context.system, this)
  val node = Node(context.system)

  def receive = {
    case GossipEnvelope(sender, gossip) ⇒ node.receive(sender, gossip)
  }

  override def unhandled(unknown: Any) = log.error("[/system/cluster/gossip] can not respond to messages - received [{}]", unknown)
}

final class ClusterDaemonSupervisor extends Actor {
  val log = Logging(context.system, this)
  val node = Node(context.system)

  private val commands = context.actorOf(Props[ClusterCommandDaemon], "commands")
  private val gossip = context.actorOf(
    Props[ClusterGossipDaemon].withRouter(RoundRobinRouter(node.clusterSettings.NrOfGossipDaemons)), "gossip")

  def receive = Actor.emptyBehavior

  override def unhandled(unknown: Any): Unit = log.error("/system/cluster can not respond to messages - received [{}]", unknown)
}

/**
 * Node Extension Id and factory for creating Node extension.
 * Example:
 * {{{
 *  val node = Node(system)
 *
 *  if (node.isLeader) { ... }
 * }}}
 */
object Node extends ExtensionId[Node] with ExtensionIdProvider {
  override def get(system: ActorSystem): Node = super.get(system)

  override def lookup = Node

  override def createExtension(system: ExtendedActorSystem): Node = new Node(system)
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
 *  val node = Node(system)
 *
 *  if (node.isLeader) { ... }
 * }}}
 */
class Node(system: ExtendedActorSystem) extends Extension {

  /**
   * Represents the state for this Node. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    self: Member,
    latestGossip: Gossip,
    memberMembershipChangeListeners: Set[MembershipChangeListener] = Set.empty[MembershipChangeListener])

  if (!system.provider.isInstanceOf[RemoteActorRefProvider])
    throw new ConfigurationException("ActorSystem[" + system + "] needs to have a 'RemoteActorRefProvider' enabled in the configuration")

  private val remote: RemoteActorRefProvider = system.provider.asInstanceOf[RemoteActorRefProvider]

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val clusterSettings = new ClusterSettings(system.settings.config, system.name)

  private val remoteAddress = remote.transport.address
  private val vclockNode = VectorClock.Node(remoteAddress.toString)

  private val gossipInitialDelay = clusterSettings.GossipInitialDelay
  private val gossipFrequency = clusterSettings.GossipFrequency

  implicit private val memberOrdering = Ordering.fromLessThan[Member](_.address.toString < _.address.toString)

  implicit private val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  val failureDetector = new AccrualFailureDetector(
    system, remoteAddress, clusterSettings.FailureDetectorThreshold, clusterSettings.FailureDetectorMaxSampleSize)

  private val nrOfDeputyNodes = clusterSettings.NrOfDeputyNodes
  private val nrOfGossipDaemons = clusterSettings.NrOfGossipDaemons
  private val nodeToJoin: Option[Address] = clusterSettings.NodeToJoin filter (_ != remoteAddress)

  private val serialization = remote.serialization

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Node")
  private val random = SecureRandom.getInstance("SHA1PRNG")

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
    new AtomicReference[State](State(member, gossip))
  }

  import Versioned.latestVersionOf

  log.info("Node [{}] - Starting cluster Node...", remoteAddress)

  // try to join the node defined in the 'akka.cluster.node-to-join' option
  autoJoin()

  // start periodic gossip to random nodes in cluster
  private val gossipCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
    gossip()
  }

  // start periodic cluster scrutinization (moving nodes condemned by the failure detector to unreachable list)
  private val scrutinizeCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
    scrutinize()
  }

  // ======================================================
  // ===================== PUBLIC API =====================
  // ======================================================

  /**
   * Latest gossip.
   */
  def latestGossip: Gossip = state.get.latestGossip

  /**
   * Member status for this node.
   */
  def self: Member = state.get.self

  /**
   * Is this node the leader?
   */
  def isLeader: Boolean = {
    val currentState = state.get
    remoteAddress == currentState.latestGossip.members.head.address
  }

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
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    // FIXME Cheating for now. Can't just shut down. Node must first gossip an Leave command, wait for Leader to do proper Handoff and then await an Exit command before switching to Removed
    if (isRunning.compareAndSet(true, false)) {
      log.info("Node [{}] - Shutting down Node and cluster daemons...", remoteAddress)
      gossipCanceller.cancel()
      scrutinizeCanceller.cancel()
      system.stop(clusterDaemons)
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
   * Send command to JOIN one node to another.
   */
  def sendJoin(address: Address) {
    clusterCommandDaemon ! ClusterAction.Join(address)
  }

  /**
   * Send command to issue state transition to LEAVING.
   */
  def sendLeave(address: Address) {
    clusterCommandDaemon ! ClusterAction.Leave(address)
  }

  /**
   * Send command to issue state transition to EXITING.
   */
  def sendDown(address: Address) {
    clusterCommandDaemon ! ClusterAction.Down(address)
  }

  /**
   * Send command to issue state transition to REMOVED.
   */
  def sendRemove(address: Address) {
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
    log.info("Node [{}] - Node [{}] is joining", remoteAddress, node)

    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val newMembers = localMembers + Member(node, MemberStatus.Joining) // add joining node as Joining
    val newGossip = localGossip copy (members = newMembers)

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
  private[cluster] final def up(address: Address) {}

  /**
   * State transition to LEAVING.
   */
  private[cluster] final def leaving(address: Address) {}

  /**
   * State transition to EXITING.
   */
  private[cluster] final def exiting(address: Address) {}

  /**
   * State transition to REMOVED.
   */
  private[cluster] final def removing(address: Address) {}

  /**
   * State transition to DOWN.
   */
  private[cluster] final def downing(address: Address) {}

  /**
   * Receive new gossip.
   */
  @tailrec
  private[cluster] final def receive(sender: Member, remoteGossip: Gossip) {
    val localState = state.get
    val localGossip = localState.latestGossip

    val winningGossip =
      if (remoteGossip.version <> localGossip.version) {
        // concurrent
        val mergedGossip = merge(remoteGossip, localGossip)
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
      log.debug("Node [{}] - Receiving gossip from [{}]", remoteAddress, sender.address)

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
    log.info("Node [{}] - Sending [{}] to [{}] through connection [{}]", remoteAddress, command, address, connection)
    connection ! command
  }

  /**
   * Initates a new round of gossip.
   */
  private def gossip() {
    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    if (!isSingletonCluster(localState)) { // do not gossip if we are a singleton cluster
      log.debug("Node [{}] - Initiating new round of gossip", remoteAddress)

      val localGossip = localState.latestGossip
      val localMembers = localGossip.members
      val localMembersSize = localMembers.size

      val localUnreachableAddresses = localGossip.overview.unreachable
      val localUnreachableSize = localUnreachableAddresses.size

      // 1. gossip to alive members
      val gossipedToDeputy = gossipToRandomNodeOf(localMembers map { _.address })

      // 2. gossip to unreachable members
      if (localUnreachableSize > 0) {
        val probability: Double = localUnreachableSize / (localMembersSize + 1)
        if (random.nextDouble() < probability) gossipToRandomNodeOf(localUnreachableAddresses)
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
   * Merges two Gossip instances including membership tables, meta-data tables and the VectorClock histories.
   */
  private def merge(gossip1: Gossip, gossip2: Gossip): Gossip = {
    val mergedVClock = gossip1.version merge gossip2.version
    val mergedMembers = gossip1.members union gossip2.members
    val mergedMeta = gossip1.meta ++ gossip2.meta
    Gossip(gossip2.overview, mergedMembers, mergedMeta, mergedVClock)
  }

  /**
   * Switches the member status.
   *
   * @param newStatus the new member status
   * @param oldState the state to change the member status in
   * @return the updated new state with the new member status
   */
  private def switchMemberStatusTo(newStatus: MemberStatus, state: State): State = {
    log.info("Node [{}] - Switching membership status to [{}]", remoteAddress, newStatus)

    val localSelf = state.self

    val localGossip = state.latestGossip
    val localMembers = localGossip.members

    val newSelf = localSelf copy (status = newStatus)
    val newMembersSet = localMembers map { member ⇒
      if (member.address == remoteAddress) newSelf
      else member
    }

    // ugly crap to work around bug in scala colletions ('val ss: SortedSet[Member] = SortedSet.empty[Member] ++ aSet' does not compile)
    val newMembersSortedSet = SortedSet[Member](newMembersSet.toList: _*)
    val newGossip = localGossip copy (members = newMembersSortedSet)

    val versionedGossip = newGossip + vclockNode
    val seenVersionedGossip = versionedGossip seen remoteAddress

    state copy (self = newSelf, latestGossip = seenVersionedGossip)
  }

  /**
   * Gossips latest gossip to an address.
   */
  private def gossipTo(address: Address) {
    val connection = clusterGossipConnectionFor(address)
    log.debug("Node [{}] - Gossiping to [{}]", remoteAddress, connection)
    connection ! GossipEnvelope(self, latestGossip)
  }

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(addresses: Iterable[Address]): Boolean = {
    val peers = addresses filterNot (_ == remoteAddress) // filter out myself
    val peer = selectRandomNode(peers)
    gossipTo(peer)
    deputyNodes exists (peer == _)
  }

  /**
   * Scrutinizes the cluster; marks members detected by the failure detector as unreachable.
   */
  @tailrec
  final private def scrutinize() {
    val localState = state.get

    if (!isSingletonCluster(localState)) { // do not scrutinize if we are a singleton cluster

      val localGossip = localState.latestGossip
      val localOverview = localGossip.overview
      val localSeen = localOverview.seen
      val localMembers = localGossip.members
      val localUnreachableAddresses = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒ failureDetector.isAvailable(member.address) }
      val newlyDetectedUnreachableAddresses = newlyDetectedUnreachableMembers map { _.address }

      if (!newlyDetectedUnreachableAddresses.isEmpty) { // we have newly detected members marked as unavailable

        val newMembers = localMembers diff newlyDetectedUnreachableMembers
        val newUnreachableAddresses: Set[Address] = localUnreachableAddresses ++ newlyDetectedUnreachableAddresses

        val newSeen = newUnreachableAddresses.foldLeft(localSeen)((currentSeen, address) ⇒ currentSeen - address)

        val newOverview = localOverview copy (seen = newSeen, unreachable = newUnreachableAddresses)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        val versionedGossip = newGossip + vclockNode
        val seenVersionedGossip = versionedGossip seen remoteAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) scrutinize() // recur
        else {
          log.info("Node [{}] - Marking node(s) an unreachable [{}]", remoteAddress, newlyDetectedUnreachableAddresses.mkString(", "))

          if (convergence(newState.latestGossip).isDefined) {
            newState.memberMembershipChangeListeners foreach { _ notify newMembers }
          }
        }
      }
    }
  }

  /**
   * Checks if we have a cluster convergence.
   *
   * @returns Some(convergedGossip) if convergence have been reached and None if not
   */
  private def convergence(gossip: Gossip): Option[Gossip] = {
    val overview = gossip.overview
    //    if (overview.unreachable.isEmpty) { // if there are any unreachable nodes then we can't have a convergence -
    // waiting for user to act (issuing DOWN) or leader to act (issuing DOWN through auto-down)
    val seen = gossip.overview.seen
    val views = seen.values.toSet
    if (views.size == 1) {
      log.debug("Node [{}] - Cluster convergence reached", remoteAddress)
      Some(gossip)
    } else None
    //    } else None
  }

  /**
   * Sets up cluster command connection.
   */
  private def clusterCommandConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "commands")

  /**
   * Sets up local cluster command connection.
   */
  private def clusterCommandDaemon = system.actorFor(RootActorPath(remoteAddress) / "system" / "cluster" / "commands")

  /**
   * Sets up cluster gossip connection.
   */
  private def clusterGossipConnectionFor(address: Address): ActorRef = system.actorFor(RootActorPath(address) / "system" / "cluster" / "gossip")

  private def deputyNodes: Iterable[Address] = state.get.latestGossip.members.toIterable map (_.address) drop 1 take nrOfDeputyNodes filter (_ != remoteAddress)

  private def selectRandomNode(addresses: Iterable[Address]): Address = addresses.toSeq(random nextInt addresses.size)

  private def isSingletonCluster(currentState: State): Boolean = currentState.latestGossip.members.size == 1
}
