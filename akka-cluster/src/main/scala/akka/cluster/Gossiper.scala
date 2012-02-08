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
import System.{ currentTimeMillis ⇒ newTimestamp }

import scala.collection.immutable.{ Map, SortedSet }
import scala.annotation.tailrec

import com.google.protobuf.ByteString

/**
 * Interface for member membership change listener.
 */
trait NodeMembershipChangeListener {
  def memberConnected(member: Member)
  def memberDisconnected(member: Member)
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
 * Represents the state of the cluster; cluster ring membership, ring convergence, meta data - all versioned by a vector clock.
 */
case class Gossip(
  self: Member,
  // sorted set of members with their status, sorted by name
  members: SortedSet[Member],
  unavailableMembers: Set[Member] = Set.empty[Member],
  // for ring convergence
  seen: Map[Member, VectorClock] = Map.empty[Member, VectorClock],
  // for handoff
  //pendingChanges: Option[Vector[PendingPartitioningChange]] = None,
  meta: Option[Map[String, Array[Byte]]] = None,
  // vector clock version
  version: VectorClock = VectorClock())
  extends ClusterMessage // is a serializable cluster message
  with Versioned // has a vector clock as version

/**
 * Represents the address and the current status of a cluster member node.
 */
case class Member(address: Address, status: MemberStatus) extends ClusterMessage

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

// sealed trait PendingPartitioningStatus
// object PendingPartitioningStatus {
//   case object Complete extends PendingPartitioningStatus
//   case object Awaiting extends PendingPartitioningStatus
// }

// case class PendingPartitioningChange(
//   owner: Address,
//   nextOwner: Address,
//   changes: Vector[VNodeMod],
//   status: PendingPartitioningStatus)

final class ClusterDaemon(system: ActorSystem, gossiper: Gossiper) extends Actor {
  val log = Logging(system, "ClusterDaemon")

  def receive = {
    case Join(address)  ⇒ gossiper.joining(address)
    case gossip: Gossip ⇒ gossiper.receive(gossip)
    case unknown        ⇒ log.error("Unknown message sent to cluster daemon [" + unknown + "]")
  }
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
 */
case class Gossiper(system: ActorSystemImpl, remote: RemoteActorRefProvider) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    latestGossip: Gossip,
    isSingletonCluster: Boolean = true, // starts as singleton cluster
    memberMembershipChangeListeners: Set[NodeMembershipChangeListener] = Set.empty[NodeMembershipChangeListener])

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val clusterSettings = new ClusterSettings(system.settings.config, system.name)

  val remoteAddress = remote.transport.address
  val memberFingerprint = remoteAddress.##

  val gossipInitialDelay = clusterSettings.GossipInitialDelay
  val gossipFrequency = clusterSettings.GossipFrequency

  implicit val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val nodeToJoin: Option[Member] =
    clusterSettings.NodeToJoin filter (_ != remoteAddress) map (address ⇒ Member(address, MemberStatus.Joining))

  private val serialization = remote.serialization
  private val failureDetector = new AccrualFailureDetector(
    system, clusterSettings.FailureDetectorThreshold, clusterSettings.FailureDetectorMaxSampleSize)

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Gossiper")
  private val random = SecureRandom.getInstance("SHA1PRNG")

  // Is it right to put this guy under the /system path or should we have a top-level /cluster or something else...?
  private val clusterDaemon = system.systemActorOf(Props(new ClusterDaemon(system, this)), "cluster")

  private val state = {
    val member = Member(remoteAddress, MemberStatus.Joining)
    val gossip = Gossip(
      self = member,
      members = SortedSet.empty[Member](Ordering.fromLessThan[Member](_.address.toString > _.address.toString)) + member) // add joining node as Joining
    new AtomicReference[State](State(gossip))
  }

  // FIXME manage connections in some other way so we can delete the RemoteConnectionManager (SINCE IT SUCKS!!!)
  private val connectionManager = new RemoteConnectionManager(system, remote, failureDetector, Map.empty[Address, ActorRef])

  log.info("Node [{}] - Starting cluster Gossiper...", remoteAddress)

  // try to join the node defined in the 'akka.cluster.node-to-join' option
  join()

  // start periodic gossip and cluster scrutinization
  val gossipCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
    gossip()
  }
  val scrutinizeCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency) {
    scrutinize()
  }

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    if (isRunning.compareAndSet(true, false)) {
      log.info("Node [{}] - Shutting down Gossiper", remoteAddress)
      try connectionManager.shutdown() finally {
        try system.stop(clusterDaemon) finally {
          try gossipCanceller.cancel() finally {
            try scrutinizeCanceller.cancel() finally {
              log.info("Node [{}] - Gossiper is shut down", remoteAddress)
            }
          }
        }
      }
    }
  }

  /**
   * Latest gossip.
   */
  def latestGossip: Gossip = state.get.latestGossip

  /**
   * Member status for this node.
   */
  def self: Member = latestGossip.self

  /**
   * Is this node a singleton cluster?
   */
  def isSingletonCluster: Boolean = state.get.isSingletonCluster

  /**
   * New node joining.
   */
  @tailrec
  final def joining(node: Address) {
    log.debug("Node [{}] - Node [{}] is joining", remoteAddress, node)
    val oldState = state.get
    val oldGossip = oldState.latestGossip
    val oldMembers = oldGossip.members
    val newGossip = oldGossip copy (members = oldMembers + Member(node, MemberStatus.Joining)) // add joining node as Joining
    val newState = oldState copy (latestGossip = incrementVersionForGossip(newGossip))
    if (!state.compareAndSet(oldState, newState)) joining(node) // recur if we failed update
  }

  /**
   * Receive new gossip.
   */
  //@tailrec
  final def receive(newGossip: Gossip) {
    val from = newGossip.self
    log.debug("Node [{}] - Receiving gossip from [{}]", remoteAddress, from.address)

    failureDetector heartbeat from.address // update heartbeat in failure detector

    // FIXME set flag state.isSingletonCluster = false (if true)

    // FIXME all below here is WRONG - redesign with cluster convergence in mind

    // val oldState = state.get
    // println("-------- NEW VERSION " + newGossip)
    // println("-------- OLD VERSION " + oldState.latestGossip)
    // val gossip = VectorClock.latestVersionOf(newGossip, oldState.latestGossip)
    // println("-------- WINNING VERSION " + gossip)

    // val latestAvailableNodes = gossip.members
    // val latestUnavailableNodes = gossip.unavailableMembers
    // println("=======>>> myself: " + myself)
    // println("=======>>> latestAvailableNodes: " + latestAvailableNodes)
    // if (!(latestAvailableNodes contains myself) && !(latestUnavailableNodes contains myself)) {
    //   println("-------- NEW NODE")
    //   // we have a new member
    //   val newGossip = gossip copy (availableNodes = latestAvailableNodes + myself)
    //   val newState = oldState copy (latestGossip = incrementVersionForGossip(newGossip))

    //   println("--------- new GOSSIP " + newGossip.members)
    //   println("--------- new STATE " + newState)
    //   // if we won the race then update else try again
    //   if (!state.compareAndSet(oldState, newState)) receive(newGossip) // recur
    //   else {
    //     println("---------- WON RACE - setting state")
    //     // create connections for all new members in the latest gossip
    //     (latestAvailableNodes + myself) foreach { member ⇒
    //       setUpConnectionTo(member)
    //       oldState.memberMembershipChangeListeners foreach (_ memberConnected member) // notify listeners about the new members
    //     }
    //   }

    // } else if (latestUnavailableNodes contains myself) {
    //   // gossip from an old former dead member

    //   val newUnavailableMembers = latestUnavailableNodes - myself
    //   val newMembers = latestAvailableNodes + myself

    //   val newGossip = gossip copy (availableNodes = newMembers, unavailableNodes = newUnavailableMembers)
    //   val newState = oldState copy (latestGossip = incrementVersionForGossip(newGossip))

    //   // if we won the race then update else try again
    //   if (!state.compareAndSet(oldState, newState)) receive(newGossip) // recur
    //   else oldState.memberMembershipChangeListeners foreach (_ memberConnected myself) // notify listeners on successful update of state
    // }
  }

  /**
   * Registers a listener to subscribe to cluster membership changes.
   */
  @tailrec
  final def registerListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.memberMembershipChangeListeners + listener
    val newState = oldState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) registerListener(listener) // recur
  }

  /**
   * Unsubscribes to cluster membership changes.
   */
  @tailrec
  final def unregisterListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.memberMembershipChangeListeners - listener
    val newState = oldState copy (memberMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Joins the pre-configured contact point and retrieves current gossip state.
   */
  private def join() = nodeToJoin foreach { member ⇒
    setUpConnectionTo(member) foreach { connection ⇒
      val command = Join(remoteAddress)
      log.info("Node [{}] - Sending [{}] to [{}] through connection [{}]", remoteAddress, command, member.address, connection)
      connection ! command
    }
  }

  /**
   * Initates a new round of gossip.
   */
  private def gossip() {
    val oldState = state.get
    val oldGossip = oldState.latestGossip

    val oldMembers = oldGossip.members
    val oldMembersSize = oldMembers.size

    val oldUnavailableMembers = oldGossip.unavailableMembers
    val oldUnavailableMembersSize = oldUnavailableMembers.size

    // 1. gossip to alive members
    val shouldGossipToDeputy =
      if (oldUnavailableMembersSize > 0) gossipToRandomNodeOf(oldMembers)
      else false

    // 2. gossip to dead members
    if (oldUnavailableMembersSize > 0) {
      val probability: Double = oldUnavailableMembersSize / (oldMembersSize + 1)
      if (random.nextDouble() < probability) gossipToRandomNodeOf(oldUnavailableMembers)
    }

    // 3. gossip to a deputy nodes for facilitating partition healing
    val deputies = deputyNodesWithoutMyself
    if ((!shouldGossipToDeputy || oldMembersSize < 1) && !deputies.isEmpty) {
      if (oldMembersSize == 0) gossipToRandomNodeOf(deputies)
      else {
        val probability = 1.0 / oldMembersSize + oldUnavailableMembersSize
        if (random.nextDouble() <= probability) gossipToRandomNodeOf(deputies)
      }
    }
  }

  /**
   * Gossips latest gossip to a member.
   */
  private def gossipTo(member: Member) {
    setUpConnectionTo(member) foreach { _ ! latestGossip }
  }

  /**
   * Gossips latest gossip to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(members: Seq[Member]): Boolean = {
    val peers = members filter (_.address != remoteAddress) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.latestGossip
    // if connection can't be established/found => ignore it since the failure detector will take care of the potential problem
    gossipTo(peer)
    deputyNodesWithoutMyself exists (peer == _)
  }

  /**
   * Gossips to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(members: Set[Member]): Boolean = gossipToRandomNodeOf(members.toList)

  /**
   * Scrutinizes the cluster; marks members detected by the failure detector as unavailable, and notifies all listeners
   * of the change in the cluster membership.
   */
  @tailrec
  final private def scrutinize() {
    val oldState = state.get
    val oldGossip = oldState.latestGossip

    val oldMembers = oldGossip.members
    val oldUnavailableMembers = oldGossip.unavailableMembers
    val newlyDetectedUnavailableMembers = oldMembers filterNot (member ⇒ failureDetector.isAvailable(member.address))

    if (!newlyDetectedUnavailableMembers.isEmpty) { // we have newly detected members marked as unavailable
      val newMembers = oldMembers diff newlyDetectedUnavailableMembers
      val newUnavailableMembers = oldUnavailableMembers ++ newlyDetectedUnavailableMembers

      val newGossip = oldGossip copy (members = newMembers, unavailableMembers = newUnavailableMembers)
      val newState = oldState copy (latestGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) scrutinize() // recur
      else {
        // notify listeners on successful update of state
        for {
          deadNode ← newUnavailableMembers
          listener ← oldState.memberMembershipChangeListeners
        } listener memberDisconnected deadNode
      }
    }
  }

  // FIXME should shuffle list randomly before start traversing to avoid connecting to some member on every member
  @tailrec
  final private def connectToRandomNodeOf(members: Seq[Member]): ActorRef = {
    members match {
      case member :: rest ⇒
        setUpConnectionTo(member) match {
          case Some(connection) ⇒ connection
          case None             ⇒ connectToRandomNodeOf(rest) // recur if
        }
      case Nil ⇒
        throw new RemoteConnectionException(
          "Could not establish connection to any of the members in the argument list")
    }
  }

  /**
   * Sets up remote connections to all the members in the argument list.
   */
  private def setUpConnectionsTo(members: Seq[Member]): Seq[Option[ActorRef]] = members map { setUpConnectionTo(_) }

  /**
   * Sets up remote connection.
   */
  private def setUpConnectionTo(member: Member): Option[ActorRef] = {
    val address = member.address
    try {
      Some(connectionManager.putIfAbsent(address, () ⇒ system.actorFor(RootActorPath(address) / "system" / "cluster")))
    } catch {
      case e: Exception ⇒ None
    }
  }

  private def incrementVersionForGossip(from: Gossip): Gossip = {
    from copy (version = from.version.increment(memberFingerprint, newTimestamp))
  }

  private def deputyNodesWithoutMyself: Seq[Member] = Seq.empty[Member] filter (_.address != remoteAddress) // FIXME read in deputy nodes from gossip data - now empty seq

  private def selectRandomNode(members: Seq[Member]): Member = members(random.nextInt(members.size))
}
