/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.actor.Status._
import akka.event.Logging
import akka.util.Duration
import akka.config.ConfigurationException

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit.SECONDS
import java.security.SecureRandom
import System.{ currentTimeMillis ⇒ newTimestamp }

import scala.collection.immutable.Map
import scala.annotation.tailrec

import java.util.concurrent.TimeoutException
import akka.dispatch.Await
import akka.pattern.ask

/**
 * Interface for node membership change listener.
 */
trait NodeMembershipChangeListener {
  def nodeConnected(node: ParsedTransportAddress)
  def nodeDisconnected(node: ParsedTransportAddress)
}

/**
 * Represents the node state of to gossip, versioned by a vector clock.
 */
case class Gossip(
  version: VectorClock,
  node: ParsedTransportAddress,
  availableNodes: Set[ParsedTransportAddress] = Set.empty[ParsedTransportAddress],
  unavailableNodes: Set[ParsedTransportAddress] = Set.empty[ParsedTransportAddress])

// ====== START - NEW GOSSIP IMPLEMENTATION ======
/*
  case class Gossip(
    version: VectorClock,
    node: ParsedTransportAddress,
    leader: ParsedTransportAddress, // FIXME leader is always head of 'members', so we probably don't need this field
    members: SortedSet[Member] = SortetSet.empty[Member](Ordering.fromLessThan[String](_ > _)), // sorted set of members with their status, sorted by name
    seen: Map[Member, VectorClock] = Map.empty[Member, VectorClock],                            // for ring convergence
    pendingChanges: Option[Vector[PendingPartitioningChange]] = None,                           // for handoff
    meta: Option[Map[String, Array[Byte]]] = None)                                              // misc meta-data

  case class Member(address: ParsedTransportAddress, status: MemberStatus)

  sealed trait MemberStatus
  object MemberStatus {
    case class Joining(version: VectorClock) extends MemberStatus
    case class Up(version: VectorClock) extends MemberStatus
    case class Leaving(version: VectorClock) extends MemberStatus
    case class Exiting(version: VectorClock) extends MemberStatus
    case class Down(version: VectorClock) extends MemberStatus
  }

  sealed trait PendingPartitioningStatus
  object PendingPartitioningStatus {
    case object Complete extends PendingPartitioningStatus
    case object Awaiting extends PendingPartitioningStatus
  }

  // FIXME what is this?
  type VNodeMod = AnyRef

  case class PendingPartitioningChange(
    owner: ParsedTransportAddress,
    nextOwner: ParsedTransportAddress,
    changes: Vector[VNodeMod],
    status: PendingPartitioningStatus)
*/
// ====== END - NEW GOSSIP IMPLEMENTATION ======

/**
 * This module is responsible for Gossiping cluster information. The abstraction maintains the list of live
 * and dead nodes. Periodically i.e. every 1 second this module chooses a random node and initiates a round
 * of Gossip with it. Whenever it gets gossip updates it updates the Failure Detector with the liveness
 * information.
 * <p/>
 * During each of these runs the node initiates gossip exchange according to following rules (as defined in the
 * Cassandra documentation [http://wiki.apache.org/cassandra/ArchitectureGossip]:
 * <pre>
 *   1) Gossip to random live node (if any)
 *   2) Gossip to random unreachable node with certain probability depending on number of unreachable and live nodes
 *   3) If the node gossiped to at (1) was not seed, or the number of live nodes is less than number of seeds,
 *       gossip to random seed with certain probability depending on number of unreachable, seed and live nodes.
 * </pre>
 */
class Gossiper(remote: Remote, system: ActorSystemImpl) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    currentGossip: Gossip,
    nodeMembershipChangeListeners: Set[NodeMembershipChangeListener] = Set.empty[NodeMembershipChangeListener])

  private val remoteSettings = remote.remoteSettings
  private val serialization = remote.serialization
  private val log = Logging(system, "Gossiper")
  private val failureDetector = remote.failureDetector
  private val connectionManager = new RemoteConnectionManager(system, remote, Map.empty[ParsedTransportAddress, ActorRef])

  private val seeds = {
    val seeds = remoteSettings.SeedNodes flatMap {
      case x: UnparsedTransportAddress ⇒
        x.parse(remote.transports) match {
          case y: ParsedTransportAddress ⇒ Some(y)
          case _                         ⇒ None
        }
      case _ ⇒ None
    }
    if (seeds.isEmpty) throw new ConfigurationException(
      "At least one seed node must be defined in the configuration [akka.cluster.seed-nodes]")
    else seeds
  }

  private val address = remote.remoteAddress
  private val nodeFingerprint = address.##

  private val random = SecureRandom.getInstance("SHA1PRNG")
  private val initalDelayForGossip = remoteSettings.InitalDelayForGossip
  private val gossipFrequency = remoteSettings.GossipFrequency

  private val state = new AtomicReference[State](State(currentGossip = newGossip()))

  {
    // start periodic gossip and cluster scrutinization - default is run them every second with 1/2 second in between
    system.scheduler.schedule(Duration(initalDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(initateGossip())
    system.scheduler.schedule(Duration(initalDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(scrutinize())
  }

  /**
   * Tell the gossiper some gossip news.
   */
  @tailrec
  final def tell(newGossip: Gossip) {
    val gossipingNode = newGossip.node

    failureDetector heartbeat gossipingNode // update heartbeat in failure detector

    val oldState = state.get
    val latestGossip = latestVersionOf(newGossip, oldState.currentGossip)
    val oldAvailableNodes = latestGossip.availableNodes
    val oldUnavailableNodes = latestGossip.unavailableNodes

    if (!(oldAvailableNodes contains gossipingNode) && !(oldUnavailableNodes contains gossipingNode)) {
      // we have a new node
      val newGossip = latestGossip copy (availableNodes = oldAvailableNodes + gossipingNode)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
      else {
        // create connections for all new nodes in the latest gossip
        for {
          node ← oldAvailableNodes
          if connectionManager.connectionFor(node).isEmpty
        } {
          val connectionFactory = () ⇒ system.actorFor(RootActorPath(RemoteSystemAddress(system.name, gossipingNode)) / "remote")
          connectionManager.putIfAbsent(node, connectionFactory) // create a new remote connection to the new node
          oldState.nodeMembershipChangeListeners foreach (_ nodeConnected node) // notify listeners about the new nodes
        }
      }

    } else if (oldUnavailableNodes contains gossipingNode) {
      // gossip from an old former dead node

      val newUnavailableNodes = oldUnavailableNodes - gossipingNode
      val newAvailableNodes = oldAvailableNodes + gossipingNode

      val newGossip = latestGossip copy (availableNodes = newAvailableNodes, unavailableNodes = newUnavailableNodes)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
      else oldState.nodeMembershipChangeListeners foreach (_ nodeConnected gossipingNode) // notify listeners on successful update of state
    }
  }

  @tailrec
  final def registerListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.nodeMembershipChangeListeners + listener
    val newState = oldState copy (nodeMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) registerListener(listener) // recur
  }

  @tailrec
  final def unregisterListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.nodeMembershipChangeListeners - listener
    val newState = oldState copy (nodeMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Initates a new round of gossip.
   */
  private def initateGossip() {
    val oldState = state.get
    val oldGossip = oldState.currentGossip

    val oldAvailableNodes = oldGossip.availableNodes
    val oldUnavailableNodes = oldGossip.unavailableNodes

    val oldAvailableNodesSize = oldAvailableNodes.size
    val oldUnavailableNodesSize = oldUnavailableNodes.size

    // 1. gossip to alive nodes
    val gossipedToSeed =
      if (oldAvailableNodesSize > 0) gossipTo(oldAvailableNodes)
      else false

    // 2. gossip to dead nodes
    if (oldUnavailableNodesSize > 0) {
      val probability: Double = oldUnavailableNodesSize / (oldAvailableNodesSize + 1)
      if (random.nextDouble() < probability) gossipTo(oldUnavailableNodes)
    }

    // 3. gossip to a seed for facilitating partition healing
    if ((!gossipedToSeed || oldAvailableNodesSize < 1) && (seeds.head != address)) {
      if (oldAvailableNodesSize == 0) gossipTo(seeds)
      else {
        val probability = 1.0 / oldAvailableNodesSize + oldUnavailableNodesSize
        if (random.nextDouble() <= probability) gossipTo(seeds)
      }
    }
  }

  /**
   * Gossips set of nodes passed in as argument. Returns 'true' if it gossiped to a "seed" node.
   */
  private def gossipTo(nodes: Set[ParsedTransportAddress]): Boolean = {
    val peers = nodes filter (_ != address) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.currentGossip

    val connection = connectionManager.connectionFor(peer).getOrElse(
      throw new IllegalStateException("Connection for [" + peer + "] is not set up"))

    try {
      val t = remoteSettings.RemoteSystemDaemonAckTimeout
      Await.result(connection.?(newGossip)(t), t) match {
        case Success(receiver) ⇒ log.debug("Gossip sent to [{}] was successfully received", receiver)
        case Failure(cause)    ⇒ log.error(cause, cause.toString)
      }
    } catch {
      case e: TimeoutException ⇒ log.error(e, "Gossip to [%s] timed out".format(connection.path))
      case e: Exception ⇒
        log.error(e, "Could not gossip to [{}] due to: {}", connection.path, e.toString)
    }

    seeds exists (peer == _)
  }

  /**
   * Scrutinizes the cluster; marks nodes detected by the failure detector as unavailable, and notifies all listeners
   * of the change in the cluster membership.
   */
  @tailrec
  final private def scrutinize() {
    val oldState = state.get
    val oldGossip = oldState.currentGossip

    val oldAvailableNodes = oldGossip.availableNodes
    val oldUnavailableNodes = oldGossip.unavailableNodes
    val newlyDetectedUnavailableNodes = oldAvailableNodes filterNot failureDetector.isAvailable

    if (!newlyDetectedUnavailableNodes.isEmpty) { // we have newly detected nodes marked as unavailable
      val newAvailableNodes = oldAvailableNodes diff newlyDetectedUnavailableNodes
      val newUnavailableNodes = oldUnavailableNodes ++ newlyDetectedUnavailableNodes

      val newGossip = oldGossip copy (availableNodes = newAvailableNodes, unavailableNodes = newUnavailableNodes)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) scrutinize() // recur
      else {
        // notify listeners on successful update of state
        for {
          deadNode ← newUnavailableNodes
          listener ← oldState.nodeMembershipChangeListeners
        } listener nodeDisconnected deadNode
      }
    }
  }

  private def newGossip(): Gossip = Gossip(
    version = VectorClock(),
    node = address.transport,
    availableNodes = Set(address.transport))

  private def incrementVersionForGossip(from: Gossip): Gossip = {
    val newVersion = from.version.increment(nodeFingerprint, newTimestamp)
    from copy (version = newVersion)
  }

  private def latestVersionOf(newGossip: Gossip, oldGossip: Gossip): Gossip = {
    (newGossip.version compare oldGossip.version) match {
      case VectorClock.After      ⇒ newGossip // gossiped version is newer, use new version
      case VectorClock.Before     ⇒ oldGossip // gossiped version is older, use old version
      case VectorClock.Concurrent ⇒ oldGossip // can't establish a causal relationship between two versions => conflict
    }
  }

  private def selectRandomNode(nodes: Set[ParsedTransportAddress]): ParsedTransportAddress = {
    nodes.toList(random.nextInt(nodes.size))
  }
}
