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

/**
 * Base trait for all cluster messages. All ClusterMessage's are serializable.
 */
sealed trait ClusterMessage extends Serializable

/**
 * Command to join the cluster.
 */
case object JoinCluster extends ClusterMessage

/**
 * Represents the state of the cluster; cluster ring membership, ring convergence, meta data - all versioned by a vector clock.
 */
case class Gossip(
  version: VectorClock = VectorClock(),
  member: Address,
  // sorted set of members with their status, sorted by name
  members: SortedSet[Member] = SortedSet.empty[Member](Ordering.fromLessThan[Member](_.address.toString > _.address.toString)),
  unavailableMembers: Set[Member] = Set.empty[Member],
  // for ring convergence
  seen: Map[Member, VectorClock] = Map.empty[Member, VectorClock],
  // for handoff
  //pendingChanges: Option[Vector[PendingPartitioningChange]] = None,
  meta: Option[Map[String, Array[Byte]]] = None)
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
sealed trait MemberStatus extends ClusterMessage with Versioned
object MemberStatus {
  case class Joining(version: VectorClock = VectorClock()) extends MemberStatus
  case class Up(version: VectorClock = VectorClock()) extends MemberStatus
  case class Leaving(version: VectorClock = VectorClock()) extends MemberStatus
  case class Exiting(version: VectorClock = VectorClock()) extends MemberStatus
  case class Down(version: VectorClock = VectorClock()) extends MemberStatus
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
    case JoinCluster ⇒ sender ! gossiper.latestGossip
    case gossip: Gossip ⇒
      gossiper.tell(gossip)

    case unknown ⇒ log.error("Unknown message sent to cluster daemon [" + unknown + "]")
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
 *   3) If the member gossiped to at (1) was not seed, or the number of live members is less than number of seeds,
 *       gossip to random seed with certain probability depending on number of unreachable, seed and live members.
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

  // configuration
  private val remoteSettings = remote.remoteSettings

  private val protocol = "akka" // TODO should this be hardcoded?
  private val address = remote.transport.address
  private val memberFingerprint = address.##

  private val serialization = remote.serialization
  private val failureDetector = new AccrualFailureDetector(remoteSettings.FailureDetectorThreshold, remoteSettings.FailureDetectorMaxSampleSize, system)

  private val initialDelayForGossip = remoteSettings.InitialDelayForGossip
  private val gossipFrequency = remoteSettings.GossipFrequency

  implicit val seedNodeConnectionTimeout = remoteSettings.SeedNodeConnectionTimeout
  implicit val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  // seed members
  private val seeds: Set[Member] = {
    if (remoteSettings.SeedNodes.isEmpty) throw new ConfigurationException(
      "At least one seed member must be defined in the configuration [akka.cluster.seed-members]")
    else remoteSettings.SeedNodes map (address ⇒ Member(address, MemberStatus.Up()))
  }

  private val isRunning = new AtomicBoolean(true)
  private val log = Logging(system, "Gossiper")
  private val random = SecureRandom.getInstance("SHA1PRNG")

  // Is it right to put this guy under the /system path or should we have a top-level /cluster or something else...?
  private val clusterDaemon = system.systemActorOf(Props(new ClusterDaemon(system, this)), "cluster")
  private val state = new AtomicReference[State](State(currentGossip = newGossip()))

  // FIXME manage connections in some other way so we can delete the RemoteConnectionManager (SINCE IT SUCKS!!!)
  private val connectionManager = new RemoteConnectionManager(system, remote, failureDetector, Map.empty[Address, ActorRef])

  log.info("Starting cluster Gossiper...")

  // join the cluster by connecting to one of the seed members and retrieve current cluster state (Gossip)
  joinCluster(Timer(remoteSettings.MaxTimeToRetryJoiningCluster))

  // start periodic gossip and cluster scrutinization
  val initateGossipCanceller = system.scheduler.schedule(
    Duration(initialDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(initateGossip())

  val scrutinizeCanceller = system.scheduler.schedule(
    Duration(initialDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(scrutinize())

  /**
   * Shuts down all connections to other members, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    if (isRunning.compareAndSet(true, false)) {
      log.info("Shutting down Gossiper for [{}]", address)
      connectionManager.shutdown()
      system.stop(clusterDaemon)
      initateGossipCanceller.cancel()
      scrutinizeCanceller.cancel()
    }
  }

  def latestGossip: Gossip = state.get.currentGossip

  /**
   * Tell the gossiper some gossip.
   */
  //@tailrec
  final def tell(newGossip: Gossip) {
    val gossipingNode = newGossip.member

    failureDetector heartbeat gossipingNode // update heartbeat in failure detector

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
    //       setUpConnectionToNode(member)
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
   * Sets up remote connections to all the members in the argument list.
   */
  private def connectToNodes(members: Seq[Member]) {
    members foreach { member ⇒
      setUpConnectionToNode(member)
      state.get.memberMembershipChangeListeners foreach (_ memberConnected member) // notify listeners about the new members
    }
  }

  // FIXME should shuffle list randomly before start traversing to avoid connecting to some member on every member
  @tailrec
  final private def connectToRandomNodeOf(members: Seq[Member]): ActorRef = {
    members match {
      case member :: rest ⇒
        setUpConnectionToNode(member) match {
          case Some(connection) ⇒ connection
          case None             ⇒ connectToRandomNodeOf(rest) // recur if
        }
      case Nil ⇒
        throw new RemoteConnectionException(
          "Could not establish connection to any of the members in the argument list")
    }
  }

  /**
   * Joins the cluster by connecting to one of the seed members and retrieve current cluster state (Gossip).
   */
  private def joinCluster(timer: Timer) {
    val seedNodes = seedNodesWithoutMyself // filter out myself

    if (!seedNodes.isEmpty) { // if we have seed members to contact
      connectToNodes(seedNodes)

      try {
        log.info("Trying to join cluster through one of the seed members [{}]", seedNodes.mkString(", "))

        Await.result(connectToRandomNodeOf(seedNodes) ? JoinCluster, seedNodeConnectionTimeout) match {
          case initialGossip: Gossip ⇒
            // just sets/overwrites the state/gossip regardless of what it was before
            // since it should be treated as the initial state
            state.set(state.get copy (currentGossip = initialGossip))
            log.debug("Received initial gossip [{}] from seed member", initialGossip)

          case unknown ⇒
            throw new IllegalStateException("Expected initial gossip from seed, received [" + unknown + "]")
        }
      } catch {
        case e: Exception ⇒
          log.error(
            "Could not join cluster through any of the seed members - retrying for another {} seconds",
            timer.timeLeft.toSeconds)

          // retry joining the cluster unless
          //   1. Gossiper is shut down
          //   2. The connection time window has expired
          if (isRunning.get) {
            if (timer.timeLeft.toMillis > 0) joinCluster(timer) // recur
            else throw new RemoteConnectionException(
              "Could not join cluster (any of the seed members) - giving up after trying for " +
                timer.timeout.toSeconds + " seconds")
          }
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
    val gossipedToSeed =
      if (oldUnavailableMembersSize > 0) gossipToRandomNodeOf(oldMembers)
      else false

    // 2. gossip to dead members
    if (oldUnavailableMembersSize > 0) {
      val probability: Double = oldUnavailableMembersSize / (oldMembersSize + 1)
      if (random.nextDouble() < probability) gossipToRandomNodeOf(oldUnavailableMembers)
    }

    // 3. gossip to a seed for facilitating partition healing
    if ((!gossipedToSeed || oldMembersSize < 1) && (seeds.head != address)) {
      if (oldMembersSize == 0) gossipToRandomNodeOf(seeds)
      else {
        val probability = 1.0 / oldMembersSize + oldUnavailableMembersSize
        if (random.nextDouble() <= probability) gossipToRandomNodeOf(seeds)
      }
    }
  }

  /**
   * Gossips to a random member in the set of members passed in as argument.
   *
   * @returns 'true' if it gossiped to a "seed" member.
   */
  private def gossipToRandomNodeOf(members: Set[Member]): Boolean = {
    val peers = members filter (_.address != address) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.currentGossip
    // if connection can't be established/found => ignore it since the failure detector will take care of the potential problem
    setUpConnectionToNode(peer) foreach { _ ! newGossip }
    seeds exists (peer == _)
  }

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

  private def setUpConnectionToNode(member: Member): Option[ActorRef] = {
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

  private def newGossip(): Gossip = Gossip(member = address)

  private def incrementVersionForGossip(from: Gossip): Gossip = {
    val newVersion = from.version.increment(memberFingerprint, newTimestamp)
    from copy (version = newVersion)
  }

  private def seedNodesWithoutMyself: List[Member] = seeds.filter(_.address != address).toList

  private def selectRandomNode(members: Set[Member]): Member = members.toList(random.nextInt(members.size))
}
