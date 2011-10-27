/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaApplication
import akka.actor._
import akka.actor.Status._
import akka.event.Logging
import akka.util.duration._
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.Random
import System.{ currentTimeMillis ⇒ newTimestamp }

import scala.collection.immutable.Map
import scala.annotation.tailrec

import com.google.protobuf.ByteString

/**
 * Interface for node membership change listener.
 */
trait NodeMembershipChangeListener {
  def nodeConnected(node: InetSocketAddress)
  def nodeDisconnected(node: InetSocketAddress)
}

/**
 * Represents the node state of to gossip, versioned by a vector clock.
 */
case class Gossip(
  version: VectorClock,
  node: InetSocketAddress,
  availableNodes: Set[InetSocketAddress] = Set.empty[InetSocketAddress],
  unavailableNodes: Set[InetSocketAddress] = Set.empty[InetSocketAddress])

/*
  // ====== NEW GOSSIP IMPLEMENTATION ======

  case class Gossip(
    version: VectorClock,
    node: InetSocketAddress,
    leader: InetSocketAddress, // FIXME leader is always head of 'members', so we probably don't need this field
    members: SortedSet[Member] = SortetSet.empty[Member](Ordering.fromLessThan[String](_ > _)), // sorted set of members with their status, sorted by name
    seen: Map[Member, VectorClock] = Map.empty[Member, VectorClock],                            // for ring convergence
    pendingChanges: Option[Vector[PendingPartitioningChange]] = None,                           // for handoff
    meta: Option[Map[String, Array[Byte]]] = None)                                              // misc meta-data

  case class Member(address: InetSocketAddress, status: MemberStatus)

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
    owner: InetSocketAddress,
    nextOwner: InetSocketAddress,
    changes: Vector[VNodeMod],
    status: PendingPartitioningStatus)
*/

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
class Gossiper(remote: Remote) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    currentGossip: Gossip,
    nodeMembershipChangeListeners: Set[NodeMembershipChangeListener] = Set.empty[NodeMembershipChangeListener])

  private val app = remote.app
  private val log = Logging(app, this)
  private val failureDetector = remote.failureDetector
  private val connectionManager = new RemoteConnectionManager(app, remote, Map.empty[InetSocketAddress, ActorRef])
  private val seeds = Set(address) // FIXME read in list of seeds from config
  private val scheduler = new DefaultScheduler

  private val address = new InetSocketAddress(app.hostname, app.port)
  private val nodeFingerprint = address.##

  private val random = new Random(newTimestamp)
  private val initalDelayForGossip = 5 seconds // FIXME make configurablev
  private val gossipFrequency = 1 seconds // FIXME make configurable
  private val timeUnit = {
    assert(gossipFrequency.unit == initalDelayForGossip.unit)
    initalDelayForGossip.unit
  }

  private val state = new AtomicReference[State](State(currentGossip = newGossip()))

  {
    // start periodic gossip and cluster scrutinization - default is run them every second with 1/2 second in between
    scheduler schedule (() ⇒ initateGossip(), initalDelayForGossip.toSeconds, gossipFrequency.toSeconds, timeUnit)
    scheduler schedule (() ⇒ scrutinize(), initalDelayForGossip.toSeconds, gossipFrequency.toSeconds, timeUnit)
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
          val connectionFactory = () ⇒ RemoteActorRef(remote.server, gossipingNode, remote.remoteDaemonServiceName, None)
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

    if (!gossipedToSeed || oldAvailableNodesSize < 1) {
      // 3. gossip to a seed for facilitating partition healing
      if (seeds.head != address) {
        if (oldAvailableNodesSize == 0) gossipTo(seeds)
        else {
          val probability = 1.0 / oldAvailableNodesSize + oldUnavailableNodesSize
          if (random.nextDouble() <= probability) gossipTo(seeds)
        }
      }
    }
  }

  /**
   * Gossips set of nodes passed in as argument. Returns 'true' if it gossiped to a "seed" node.
   */
  private def gossipTo(nodes: Set[InetSocketAddress]): Boolean = {
    val peers = nodes filter (_ != address) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.currentGossip

    val connection = connectionManager.connectionFor(peer).getOrElse(
      throw new IllegalStateException("Connection for [" + peer + "] is not set up"))

    try {
      (connection ? (toRemoteMessage(newGossip), remote.remoteSystemDaemonAckTimeout)).as[Status] match {
        case Some(Success(receiver)) ⇒
          log.debug("Gossip sent to [{}] was successfully received", receiver)

        case Some(Failure(cause)) ⇒
          log.error(cause, cause.toString)
          throw cause

        case None ⇒
          val error = new RemoteException("Gossip to [{}] timed out".format(connection.address))
          log.error(error, error.toString)
          throw error
      }
    } catch {
      case e: Exception ⇒
        log.error(e, "Could not gossip to [{}] due to: {}", connection.address, e.toString)
        throw e
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
    val newlyDetectedUnavailableNodes = oldAvailableNodes filter (!failureDetector.isAvailable(_))

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
    node = address,
    availableNodes = Set(address))

  private def incrementVersionForGossip(from: Gossip): Gossip = {
    val newVersion = from.version.increment(nodeFingerprint, newTimestamp)
    from copy (version = newVersion)
  }

  private def toRemoteMessage(gossip: Gossip): RemoteProtocol.RemoteSystemDaemonMessageProtocol = {
    val gossipAsBytes = app.serialization.serialize(gossip) match {
      case Left(error)  ⇒ throw error
      case Right(bytes) ⇒ bytes
    }

    RemoteSystemDaemonMessageProtocol.newBuilder
      .setMessageType(GOSSIP)
      .setActorAddress(remote.remoteDaemonServiceName)
      .setPayload(ByteString.copyFrom(gossipAsBytes))
      .build()
  }

  private def latestVersionOf(newGossip: Gossip, oldGossip: Gossip): Gossip = {
    (newGossip.version compare oldGossip.version) match {
      case VectorClock.After      ⇒ newGossip // gossiped version is newer, use new version
      case VectorClock.Before     ⇒ oldGossip // gossiped version is older, use old version
      case VectorClock.Concurrent ⇒ oldGossip // can't establish a causal relationship between two versions => conflict
    }
  }

  private def selectRandomNode(nodes: Set[InetSocketAddress]): InetSocketAddress = {
    nodes.toList(random.nextInt(nodes.size))
  }
}
