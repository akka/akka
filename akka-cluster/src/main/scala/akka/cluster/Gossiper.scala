/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor._
import akka.actor.Status._
import akka.remote._
import akka.event.Logging
import akka.dispatch.Await
import akka.pattern.ask
import akka.util._
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
trait MembershipChangeListener { // FIXME add notification of MembershipChangeListener
  def notify(members: SortedSet[Member]): Unit
  // def memberConnected(member: Member): Unit
  // def memberDisconnected(member: Member): Unit
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
 * Command to join the cluster.
 */
case class Join(node: Address) extends ClusterMessage

/**
 * Command to leave the cluster.
 */
case class Leave(node: Address) extends ClusterMessage

/**
 * Command to mark node as temporay down.
 */
case class Down(node: Address) extends ClusterMessage

/**
 * Command to remove a node from the cluster immediately.
 */
case class Remove(node: Address) extends ClusterMessage

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
}

// sealed trait PartitioningStatus
// object PartitioningStatus {
//   case object Complete extends PartitioningStatus
//   case object Awaiting extends PartitioningStatus
// }

// case class PartitioningChange(
//   from: Address,
//   to: Address,
//   path: PartitionPath,
//   status: PartitioningStatus)

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
  //partitions: Tree[PartitionPath, Node] = Tree.empty[PartitionPath, Node],
  //pending: Set[PartitioningChange] = Set.empty[PartitioningChange],
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
  def seen(address: Address): Gossip =
    this copy (overview = overview copy (seen = overview.seen + (address -> version)))

  override def toString =
    "Gossip(" +
      "overview = " + overview +
      ", members = [" + members.mkString(", ") +
      "], meta = [" + meta.mkString(", ") +
      "], version = " + version +
      ")"
}

// FIXME add FSM trait?
final class ClusterDaemon(system: ActorSystem, gossiper: Gossiper) extends Actor {
  val log = Logging(system, "ClusterDaemon")

  def receive = {
    case GossipEnvelope(sender, gossip) ⇒ gossiper.receive(sender, gossip)
    case Join(address)                  ⇒ gossiper.joining(address)
    case Leave(address)                 ⇒ //gossiper.leaving(address)
    case Down(address)                  ⇒ //gossiper.downing(address)
    case Remove(address)                ⇒ //gossiper.removing(address)
    case unknown                        ⇒ log.error("Unknown message sent to cluster daemon [" + unknown + "]")
  }
}

// FIXME Cluster public API should be an Extension
// FIXME Add cluster Node class and refactor out all non-gossip related stuff out of Gossiper

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
 */
case class Gossiper(system: ActorSystemImpl, remote: RemoteActorRefProvider) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    self: Member,
    latestGossip: Gossip,
    memberMembershipChangeListeners: Set[MembershipChangeListener] = Set.empty[MembershipChangeListener])

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val clusterSettings = new ClusterSettings(system.settings.config, system.name)

  val remoteAddress = remote.transport.address
  val selfNode = VectorClock.Node(remoteAddress.toString)

  val gossipInitialDelay = clusterSettings.GossipInitialDelay
  val gossipFrequency = clusterSettings.GossipFrequency

  implicit val memberOrdering = Ordering.fromLessThan[Member](_.address.toString < _.address.toString)

  implicit val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val nodeToJoin: Option[Address] = clusterSettings.NodeToJoin filter (_ != remoteAddress)

  private val serialization = remote.serialization
  private val failureDetector = new AccrualFailureDetector(
    system, clusterSettings.FailureDetectorThreshold, clusterSettings.FailureDetectorMaxSampleSize)

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Gossiper")
  private val random = SecureRandom.getInstance("SHA1PRNG")

  // Is it right to put this guy under the /system path or should we have a top-level /cluster or something else...?
  // FIXME should be defined as a router so we get concurrency here
  private val clusterDaemon = system.systemActorOf(Props(new ClusterDaemon(system, this)), "cluster")

  private val state = {
    val member = Member(remoteAddress, MemberStatus.Joining)
    val gossip = Gossip(members = SortedSet.empty[Member] + member) + selfNode // add me as member and update my vector clock
    new AtomicReference[State](State(member, gossip))
  }

  // FIXME manage connections in some other way so we can delete the RemoteConnectionManager (SINCE IT SUCKS!!!)
  private val connectionManager = new RemoteConnectionManager(system, remote, failureDetector, Map.empty[Address, ActorRef])

  import Versioned.latestVersionOf

  log.info("Node [{}] - Starting cluster Gossiper...", remoteAddress)

  // try to join the node defined in the 'akka.cluster.node-to-join' option
  nodeToJoin foreach join

  // start periodic gossip to random nodes in cluster
  val gossipCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
    gossip()
  }

  // start periodic cluster scrutinization (moving nodes condemned by the failure detector to unreachable list)
  val scrutinizeCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
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
   * Is this node a singleton cluster?
   */
  def isSingletonCluster: Boolean = isSingletonCluster(state.get)

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {

    // FIXME Cheating for now. Can't just shut down. Node must first gossip an Leave command, wait for Leader to do proper Handoff and then await an Exit command before switching to Removed

    if (isRunning.compareAndSet(true, false)) {
      log.info("Node [{}] - Shutting down Gossiper and ClusterDaemon...", remoteAddress)

      try connectionManager.shutdown() finally {
        try system.stop(clusterDaemon) finally {
          try gossipCanceller.cancel() finally {
            try scrutinizeCanceller.cancel() finally {
              log.info("Node [{}] - Gossiper and ClusterDaemon shut down successfully", remoteAddress)
            }
          }
        }
      }
    }
  }

  /**
   * New node joining.
   */
  @tailrec
  final def joining(node: Address) {
    log.info("Node [{}] - Node [{}] is joining", remoteAddress, node)

    val localState = state.get
    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val newMembers = localMembers + Member(node, MemberStatus.Joining) // add joining node as Joining
    val newGossip = localGossip copy (members = newMembers)

    val versionedGossip = newGossip + selfNode
    val seenVersionedGossip = versionedGossip seen remoteAddress

    val newState = localState copy (latestGossip = seenVersionedGossip)

    if (!state.compareAndSet(localState, newState)) joining(node) // recur if we failed update
  }

  /**
   * Receive new gossip.
   */
  @tailrec
  final def receive(sender: Member, remoteGossip: Gossip) {
    log.debug("Node [{}] - Receiving gossip from [{}]", remoteAddress, sender.address)

    failureDetector heartbeat sender.address // update heartbeat in failure detector

    // FIXME check for convergence - if we have convergence then trigger the listeners

    val localState = state.get
    val localGossip = localState.latestGossip

    val winningGossip =
      if (remoteGossip.version <> localGossip.version) {
        // concurrent
        val mergedGossip = merge(remoteGossip, localGossip)
        val versionedMergedGossip = mergedGossip + selfNode

        log.debug("Can't establish a causal relationship between \"remote\" gossip [{}] and \"local\" gossip [{}] - merging them into [{}]",
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

  // ========================================================
  // ===================== INTERNAL API =====================
  // ========================================================

  /**
   * Joins the pre-configured contact point and retrieves current gossip state.
   */
  private def join(address: Address) {
    setUpConnectionTo(address) foreach { connection ⇒
      val command = Join(remoteAddress)
      log.info("Node [{}] - Sending [{}] to [{}]", remoteAddress, command, address)
      connection ! command
    }

    contactPoint match {
      case None ⇒ log.info("Booting up in singleton cluster mode")
      case Some(member) ⇒
        log.info("Trying to join contact point node defined in the configuration [{}]", member)
        setUpConnectionTo(member) match {
          case None             ⇒ log.error("Could not set up connection to join contact point node defined in the configuration [{}]", member)
          case Some(connection) ⇒ tryJoinContactPoint(connection, deadline)
        }
    }
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
      val gossipedToDeputy = gossipToRandomNodeOf(localMembers.toList map { _.address })

      // 2. gossip to unreachable members
      if (localUnreachableSize > 0) {
        val probability: Double = localUnreachableSize / (localMembersSize + 1)
        if (random.nextDouble() < probability) gossipToRandomNodeOf(localUnreachableAddresses.toList)
      }

      // 3. gossip to a deputy nodes for facilitating partition healing
      val deputies = deputyNodesWithoutMyself
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
   * Switches the state in the FSM.
   */
  @tailrec
  final private def switchStatusTo(newStatus: MemberStatus) {
    log.info("Node [{}] - Switching membership status to [{}]", remoteAddress, newStatus)

    val localState = state.get
    val localSelf = localState.self

    val localGossip = localState.latestGossip
    val localMembers = localGossip.members

    val newSelf = localSelf copy (status = newStatus)
    val newMembersSet = localMembers map { member ⇒
      if (member.address == remoteAddress) newSelf
      else member
    }

    // ugly crap to work around bug in scala colletions ('val ss: SortedSet[Member] = SortedSet.empty[Member] ++ aSet' does not compile)
    val newMembersSortedSet = SortedSet[Member](newMembersSet.toList: _*)
    val newGossip = localGossip copy (members = newMembersSortedSet)

    val versionedGossip = newGossip + selfNode
    val seenVersionedGossip = versionedGossip seen remoteAddress

    val newState = localState copy (self = newSelf, latestGossip = seenVersionedGossip)

    if (!state.compareAndSet(localState, newState)) switchStatusTo(newStatus) // recur if we failed update
  }

  /**
   * Gossips latest gossip to an address.
   */
  private def gossipTo(address: Address) {
    setUpConnectionTo(address) foreach { connection ⇒
      log.debug("Node [{}] - Gossiping to [{}]", remoteAddress, address)
      connection ! GossipEnvelope(self, latestGossip)
    }
  }

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(addresses: Seq[Address]): Boolean = {
    val peers = addresses filter (_ != remoteAddress) // filter out myself
    val peer = selectRandomNode(peers)
    val localState = state.get
    val localGossip = localState.latestGossip
    // if connection can't be established/found => ignore it since the failure detector will take care of the potential problem
    gossipTo(peer)
    deputyNodesWithoutMyself exists (peer == _)
  }

  /**
   * Scrutinizes the cluster; marks members detected by the failure detector as unavailable.
   */
  @tailrec
  final private def scrutinize() {
    val localState = state.get

    if (!isSingletonCluster(localState)) { // do not scrutinize if we are a singleton cluster

      val localGossip = localState.latestGossip
      val localOverview = localGossip.overview
      val localMembers = localGossip.members
      val localUnreachableAddresses = localGossip.overview.unreachable

      val newlyDetectedUnreachableMembers = localMembers filterNot { member ⇒ failureDetector.isAvailable(member.address) }
      val newlyDetectedUnreachableAddresses = newlyDetectedUnreachableMembers map { _.address }

      if (!newlyDetectedUnreachableAddresses.isEmpty) { // we have newly detected members marked as unavailable

        val newMembers = localMembers diff newlyDetectedUnreachableMembers
        val newUnreachableAddresses: Set[Address] = (localUnreachableAddresses ++ newlyDetectedUnreachableAddresses)

        val newOverview = localOverview copy (unreachable = newUnreachableAddresses)
        val newGossip = localGossip copy (overview = newOverview, members = newMembers)

        val versionedGossip = newGossip + selfNode
        val seenVersionedGossip = versionedGossip seen remoteAddress

        val newState = localState copy (latestGossip = seenVersionedGossip)

        // if we won the race then update else try again
        if (!state.compareAndSet(localState, newState)) scrutinize() // recur
        else {
          // FIXME should only notify when there is a cluster convergence
          // notify listeners on successful update of state
          // for {
          //   deadNode ← newUnreachableAddresses
          //   listener ← localState.memberMembershipChangeListeners
          // } listener memberDisconnected deadNode
        }
      }
    }
  }

  // FIXME should shuffle list randomly before start traversing to avoid connecting to some member on every member
  @tailrec
  final private def connectToRandomNodeOf(addresses: Seq[Address]): ActorRef = {
    addresses match {

      case address :: rest ⇒
        setUpConnectionTo(address) match {
          case Some(connection) ⇒ connection
          case None             ⇒ connectToRandomNodeOf(rest) // recur - if we could not set up a connection - try next address
        }

      case Nil ⇒
        throw new RemoteConnectionException(
          "Could not establish connection to any of the addresses in the argument list [" + addresses.mkString(", ") + "]")
    }
  }

  /**
   * Sets up remote connections to all the addresses in the argument list.
   */
  private def setUpConnectionsTo(addresses: Seq[Address]): Seq[Option[ActorRef]] = addresses map setUpConnectionTo

  /**
   * Sets up remote connection.
   */
  private def setUpConnectionTo(address: Address): Option[ActorRef] = Option {
    // FIXME no need for using a factory here - remove connectionManager
    try connectionManager.putIfAbsent(address, () ⇒ system.actorFor(RootActorPath(address) / "system" / "cluster")) catch {
      case e: Exception ⇒ null
    }
  }

  private def deputyNodesWithoutMyself: Seq[Address] = Seq.empty[Address] filter (_ != remoteAddress) // FIXME read in deputy nodes from gossip data - now empty seq

  private def selectRandomNode(addresses: Seq[Address]): Address = addresses(random nextInt addresses.size)

  private def isSingletonCluster(currentState: State): Boolean = currentState.latestGossip.members.size == 1
}
