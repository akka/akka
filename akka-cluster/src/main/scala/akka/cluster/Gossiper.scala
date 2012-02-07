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
  member: Member,
  // sorted set of members with their status, sorted by name
  members: SortedSet[Member] = SortedSet.empty[Member](Ordering.fromLessThan[Member](_.address.toString > _.address.toString)),
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
    case Join(address)  ⇒ sender ! gossiper.latestGossip // TODO use address in Join(address) ?
    case gossip: Gossip ⇒ gossiper.tell(gossip)
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
case class Gossiper(remote: RemoteActorRefProvider, system: ActorSystemImpl) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    currentGossip: Gossip,
    memberMembershipChangeListeners: Set[NodeMembershipChangeListener] = Set.empty[NodeMembershipChangeListener])

  val remoteSettings = new RemoteSettings(system.settings.config, system.name)
  val clusterSettings = new ClusterSettings(system.settings.config, system.name)

  val protocol = "akka" // TODO should this be hardcoded?
  val address = remote.transport.address
  val memberFingerprint = address.##

  val gossipInitialDelay = clusterSettings.GossipInitialDelay
  val gossipFrequency = clusterSettings.GossipFrequency

  implicit val joinTimeout = clusterSettings.JoinTimeout
  implicit val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  private val contactPoint: Option[Member] =
    clusterSettings.JoinContactPoint filter (_ != address) map (address ⇒ Member(address, MemberStatus.Up))

  private val serialization = remote.serialization
  private val failureDetector = new AccrualFailureDetector(
    system, clusterSettings.FailureDetectorThreshold, clusterSettings.FailureDetectorMaxSampleSize)

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Gossiper")
  private val random = SecureRandom.getInstance("SHA1PRNG")

  // Is it right to put this guy under the /system path or should we have a top-level /cluster or something else...?
  private val clusterDaemon = system.systemActorOf(Props(new ClusterDaemon(system, this)), "cluster")
  private val state = new AtomicReference[State](State(currentGossip = newGossip()))

  // FIXME manage connections in some other way so we can delete the RemoteConnectionManager (SINCE IT SUCKS!!!)
  private val connectionManager = new RemoteConnectionManager(system, remote, failureDetector, Map.empty[Address, ActorRef])

  log.info("Starting cluster Gossiper...")

  // join the cluster by connecting to one of the deputy members and retrieve current cluster state (Gossip)
  joinContactPoint(clusterSettings.JoinMaxTimeToRetry fromNow)

  // start periodic gossip and cluster scrutinization
  val initateGossipCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency)(initateGossip())
  val scrutinizeCanceller = system.scheduler.schedule(gossipInitialDelay, gossipFrequency)(scrutinize())

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    if (isRunning.compareAndSet(true, false)) {
      log.info("Shutting down Gossiper for [{}]...", address)
      try connectionManager.shutdown() finally {
        try system.stop(clusterDaemon) finally {
          try initateGossipCanceller.cancel() finally {
            try scrutinizeCanceller.cancel() finally {
              log.info("Gossiper for [{}] is shut down", address)
            }
          }
        }
      }
    }
  }

  def latestGossip: Gossip = state.get.currentGossip

  /**
   * Tell the gossiper some gossip.
   */
  //@tailrec
  final def tell(newGossip: Gossip) {
    val gossipingNode = newGossip.member

    failureDetector heartbeat gossipingNode.address // update heartbeat in failure detector

    // FIXME all below here is WRONG - redesign with cluster convergence in mind

    // val oldState = state.get
    // println("-------- NEW VERSION " + newGossip)
    // println("-------- OLD VERSION " + oldState.currentGossip)
    // val latestGossip = VectorClock.latestVersionOf(newGossip, oldState.currentGossip)
    // println("-------- WINNING VERSION " + latestGossip)

    // val latestAvailableNodes = latestGossip.members
    // val latestUnavailableNodes = latestGossip.unavailableMembers
    // println("=======>>> gossipingNode: " + gossipingNode)
    // println("=======>>> latestAvailableNodes: " + latestAvailableNodes)
    // if (!(latestAvailableNodes contains gossipingNode) && !(latestUnavailableNodes contains gossipingNode)) {
    //   println("-------- NEW NODE")
    //   // we have a new member
    //   val newGossip = latestGossip copy (availableNodes = latestAvailableNodes + gossipingNode)
    //   val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

    //   println("--------- new GOSSIP " + newGossip.members)
    //   println("--------- new STATE " + newState)
    //   // if we won the race then update else try again
    //   if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
    //   else {
    //     println("---------- WON RACE - setting state")
    //     // create connections for all new members in the latest gossip
    //     (latestAvailableNodes + gossipingNode) foreach { member ⇒
    //       setUpConnectionTo(member)
    //       oldState.memberMembershipChangeListeners foreach (_ memberConnected member) // notify listeners about the new members
    //     }
    //   }

    // } else if (latestUnavailableNodes contains gossipingNode) {
    //   // gossip from an old former dead member

    //   val newUnavailableMembers = latestUnavailableNodes - gossipingNode
    //   val newMembers = latestAvailableNodes + gossipingNode

    //   val newGossip = latestGossip copy (availableNodes = newMembers, unavailableNodes = newUnavailableMembers)
    //   val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

    //   // if we won the race then update else try again
    //   if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
    //   else oldState.memberMembershipChangeListeners foreach (_ memberConnected gossipingNode) // notify listeners on successful update of state
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
  private def joinContactPoint(deadline: Deadline) {
    def tryJoinContactPoint(connection: ActorRef, deadline: Deadline) {
      try {
        Await.result(connection ? Join(address), joinTimeout) match {
          case initialGossip: Gossip ⇒
            // just sets/overwrites the state/gossip regardless of what it was before
            // since it should be treated as the initial state
            state.set(state.get copy (currentGossip = initialGossip))
            log.debug("Received initial gossip [{}]", initialGossip)

          case unknown ⇒
            throw new IllegalStateException("Expected initial gossip but received [" + unknown + "]")
        }
      } catch {
        case e: Exception ⇒
          log.error("Could not join contact point node - retrying for another {} seconds", deadline.timeLeft.toSeconds)

          // retry joining the cluster unless
          //   1. Gossiper is shut down
          //   2. The connection time window has expired
          if (isRunning.get && deadline.timeLeft.toMillis > 0) tryJoinContactPoint(connection, deadline) // recur
          else throw new RemoteConnectionException(
            "Could not join contact point node - giving up after trying for " + deadline.time.toSeconds + " seconds")
      }
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
  private def initateGossip() {
    val oldState = state.get
    val oldGossip = oldState.currentGossip

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
    if ((!shouldGossipToDeputy || oldMembersSize < 1) && (deputies.head != address)) {
      if (oldMembersSize == 0) gossipToRandomNodeOf(deputies)
      else {
        val probability = 1.0 / oldMembersSize + oldUnavailableMembersSize
        if (random.nextDouble() <= probability) gossipToRandomNodeOf(deputies)
      }
    }
  }

  /**
   * Gossips to a random member in the set of members passed in as argument.
   *
   * @return 'true' if it gossiped to a "deputy" member.
   */
  private def gossipToRandomNodeOf(members: Seq[Member]): Boolean = {
    val peers = members filter (_.address != address) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.currentGossip
    // if connection can't be established/found => ignore it since the failure detector will take care of the potential problem
    setUpConnectionTo(peer) foreach { _ ! newGossip }
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
    val oldGossip = oldState.currentGossip

    val oldMembers = oldGossip.members
    val oldUnavailableMembers = oldGossip.unavailableMembers
    val newlyDetectedUnavailableMembers = oldMembers filterNot (member ⇒ failureDetector.isAvailable(member.address))

    if (!newlyDetectedUnavailableMembers.isEmpty) { // we have newly detected members marked as unavailable
      val newMembers = oldMembers diff newlyDetectedUnavailableMembers
      val newUnavailableMembers = oldUnavailableMembers ++ newlyDetectedUnavailableMembers

      val newGossip = oldGossip copy (members = newMembers, unavailableMembers = newUnavailableMembers)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

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
      Some(
        connectionManager.putIfAbsent(
          address,
          () ⇒ system.actorFor(RootActorPath(Address(protocol, system.name)) / "system" / "cluster")))
    } catch {
      case e: Exception ⇒ None
    }
  }

  private def newGossip(): Gossip = Gossip(Member(address, MemberStatus.Joining)) // starts in Joining mode

  private def incrementVersionForGossip(from: Gossip): Gossip = {
    from copy (version = from.version.increment(memberFingerprint, newTimestamp))
  }

  private def deputyNodesWithoutMyself: Seq[Member] = Seq.empty[Member] filter (_.address != address) // FIXME read in deputy nodes from gossip data - now empty seq

  private def selectRandomNode(members: Seq[Member]): Member = members(random.nextInt(members.size))
}
