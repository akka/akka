/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor._
import akka.actor.Status._
import akka.event.Logging
import akka.util._
import akka.dispatch.Await
import akka.config.ConfigurationException

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit._
import java.util.concurrent.TimeoutException
import java.security.SecureRandom
import System.{ currentTimeMillis ⇒ newTimestamp }

import scala.collection.immutable.Map
import scala.annotation.tailrec

import akka.dispatch.Await
import akka.pattern.ask

import com.google.protobuf.ByteString

/**
 * Interface for node membership change listener.
 */
trait NodeMembershipChangeListener {
  def nodeConnected(node: Address)
  def nodeDisconnected(node: Address)
}

/**
 * Represents the node state of to gossip, versioned by a vector clock.
 */
case class Gossip(
  version: VectorClock,
  node: Address,
  availableNodes: Set[Address] = Set.empty[Address],
  unavailableNodes: Set[Address] = Set.empty[Address])

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
 * Interface for node membership change listener.
 */
trait NodeMembershipChangeListener {
  def nodeConnected(node: ParsedTransportAddress)
  def nodeDisconnected(node: ParsedTransportAddress)
}

sealed trait ClusterMessage extends Serializable

case object JoinCluster extends ClusterMessage

/**
 * Represents the node state of to gossip, versioned by a vector clock.
 */
case class Gossip(
  version: VectorClock,
  node: ParsedTransportAddress,
  availableNodes: Set[ParsedTransportAddress] = Set.empty[ParsedTransportAddress],
  unavailableNodes: Set[ParsedTransportAddress] = Set.empty[ParsedTransportAddress]) extends ClusterMessage

class ClusterDaemon(system: ActorSystem, gossiper: Gossiper) extends Actor {
  val log = Logging(system, "ClusterDaemon")

  def receive = {
    case JoinCluster    ⇒ sender ! gossiper.latestGossip
    case gossip: Gossip ⇒ gossiper.tell(gossip)
    case unknown        ⇒ log.error("Unknown message sent to cluster daemon [" + unknown + "]")
  }
}

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
case class Gossiper(remote: Remote, system: ActorSystemImpl) {

  /**
   * Represents the state for this Gossiper. Implemented using optimistic lockless concurrency,
   * all state is represented by this immutable case class and managed by an AtomicReference.
   */
  private case class State(
    currentGossip: Gossip,
    nodeMembershipChangeListeners: Set[NodeMembershipChangeListener] = Set.empty[NodeMembershipChangeListener])

  // configuration
  private val remoteSettings = remote.remoteSettings
  private val serialization = remote.serialization
  private val failureDetector = remote.failureDetector

  private val initalDelayForGossip = remoteSettings.InitalDelayForGossip
  private val gossipFrequency = remoteSettings.GossipFrequency

  implicit val seedNodeConnectionTimeout = remoteSettings.SeedNodeConnectionTimeout
  implicit val defaultTimeout = Timeout(remoteSettings.RemoteSystemDaemonAckTimeout)

  // seed nodes
  private val seeds: Set[ParsedTransportAddress] = {
    val seeds = remoteSettings.SeedNodes flatMap {
      case uta: UnparsedTransportAddress ⇒
        uta.parse(remote.transports) match {
          case pta: ParsedTransportAddress ⇒ Some(pta)
          case _                           ⇒ None
        }
      case _ ⇒ None
    }
    if (remoteSettings.SeedNodes.isEmpty) throw new ConfigurationException(
      "At least one seed node must be defined in the configuration [akka.cluster.seed-nodes]")
    else remoteSettings.SeedNodes
  }

  private val log = Logging(system, "Gossiper")
  private val random = SecureRandom.getInstance("SHA1PRNG")
  private val connectionManager = new RemoteConnectionManager(system, remote, Map.empty[Address, ActorRef])
  private val clusterDaemon = system.systemActorOf(Props(new ClusterDaemon(system, this)), "cluster")
  private val state = new AtomicReference[State](State(currentGossip = newGossip()))

  log.info("Starting cluster Gossiper...")

  // join the cluster by connecting to one of the seed nodes and retrieve current cluster state (Gossip)
  joinCluster(Timer(remoteSettings.MaxTimeToRetryJoiningCluster))

  // start periodic gossip and cluster scrutinization - default is run them every second with 1/2 second in between
  val initateGossipCanceller = system.scheduler.schedule(
    Duration(initalDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(initateGossip())
  val scrutinizeCanceller = system.scheduler.schedule(
    Duration(initalDelayForGossip.toSeconds, SECONDS), Duration(gossipFrequency.toSeconds, SECONDS))(scrutinize())

  /**
   * Shuts down all connections to other nodes, the cluster daemon and the periodic gossip and cleanup tasks.
   */
  def shutdown() {
    connectionManager.shutdown()
    system.stop(clusterDaemon)
    initateGossipCanceller.cancel()
    scrutinizeCanceller.cancel()
  }

  def latestGossip: Gossip = state.get.currentGossip

  /**
   * Tell the gossiper some gossip.
   */
  @tailrec
  final def tell(newGossip: Gossip) {
    val gossipingNode = newGossip.node

    failureDetector heartbeat gossipingNode // update heartbeat in failure detector

    val oldState = state.get
    val latestGossip = latestVersionOf(newGossip, oldState.currentGossip)
    val latestAvailableNodes = latestGossip.availableNodes
    val latestUnavailableNodes = latestGossip.unavailableNodes

    if (!(latestAvailableNodes contains gossipingNode) && !(latestUnavailableNodes contains gossipingNode)) {
      // we have a new node
      val newGossip = latestGossip copy (availableNodes = latestAvailableNodes + gossipingNode)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
      else {
        // create connections for all new nodes in the latest gossip
        (latestAvailableNodes + gossipingNode) foreach { node ⇒
          setUpConnectionToNode(node)
          oldState.nodeMembershipChangeListeners foreach (_ nodeConnected node) // notify listeners about the new nodes
        }
      }

    } else if (latestUnavailableNodes contains gossipingNode) {
      // gossip from an old former dead node

      val newUnavailableNodes = latestUnavailableNodes - gossipingNode
      val newAvailableNodes = latestAvailableNodes + gossipingNode

      val newGossip = latestGossip copy (availableNodes = newAvailableNodes, unavailableNodes = newUnavailableNodes)
      val newState = oldState copy (currentGossip = incrementVersionForGossip(newGossip))

      // if we won the race then update else try again
      if (!state.compareAndSet(oldState, newState)) tell(newGossip) // recur
      else oldState.nodeMembershipChangeListeners foreach (_ nodeConnected gossipingNode) // notify listeners on successful update of state
    }
  }

  /**
   * Registers a listener to subscribe to cluster membership changes.
   */
  @tailrec
  final def registerListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.nodeMembershipChangeListeners + listener
    val newState = oldState copy (nodeMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) registerListener(listener) // recur
  }

  /**
   * Unsubscribes to cluster membership changes.
   */
  @tailrec
  final def unregisterListener(listener: NodeMembershipChangeListener) {
    val oldState = state.get
    val newListeners = oldState.nodeMembershipChangeListeners - listener
    val newState = oldState copy (nodeMembershipChangeListeners = newListeners)
    if (!state.compareAndSet(oldState, newState)) unregisterListener(listener) // recur
  }

  /**
   * Sets up remote connections to all the nodes in the argument list.
   */
  private def connectToNodes(nodes: Seq[ParsedTransportAddress]) {
    nodes foreach { node ⇒
      setUpConnectionToNode(node)
      state.get.nodeMembershipChangeListeners foreach (_ nodeConnected node) // notify listeners about the new nodes
    }
  }

  // FIXME should shuffle list randomly before start traversing to avoid connecting to some node on every node
  @tailrec
  final private def connectToRandomNodeOf(nodes: Seq[ParsedTransportAddress]): ActorRef = {
    nodes match {
      case node :: rest ⇒
        setUpConnectionToNode(node) match {
          case Some(connection) ⇒ connection
          case None             ⇒ connectToRandomNodeOf(rest) // recur if
        }
      case Nil ⇒
        throw new RemoteConnectionException(
          "Could not establish connection to any of the nodes in the argument list")
    }
  }

  /**
   * Joins the cluster by connecting to one of the seed nodes and retrieve current cluster state (Gossip).
   */
  private def joinCluster(timer: Timer) {
    val seedNodes = seedNodesWithoutMyself // filter out myself

    if (!seedNodes.isEmpty) { // if we have seed nodes to contact
      connectToNodes(seedNodes)

      try {
        log.info("Trying to join cluster through one of the seed nodes [{}]", seedNodes.mkString(", "))

        Await.result(connectToRandomNodeOf(seedNodes) ? JoinCluster, seedNodeConnectionTimeout) match {
          case initialGossip: Gossip ⇒
            // just sets/overwrites the state/gossip regardless of what it was before
            // since it should be treated as the initial state
            state.set(state.get copy (currentGossip = initialGossip))
            log.debug("Received initial gossip [{}] from seed node", initialGossip)

          case unknown ⇒
            throw new IllegalStateException("Expected initial gossip from seed, received [" + unknown + "]")
        }
      } catch {
        case e: Exception ⇒
          log.error(
            "Could not join cluster through any of the seed nodes - retrying for another {} seconds",
            timer.timeLeft.toSeconds)

          if (timer.timeLeft.toMillis > 0) joinCluster(timer) // recur - retry joining the cluster
          else throw new RemoteConnectionException(
            "Could not join cluster (any of the seed nodes) - giving up after trying for " +
              timer.timeout.toSeconds + " seconds")
      }
    }
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
      if (oldAvailableNodesSize > 0) gossipToRandomNodeOf(oldAvailableNodes)
      else false

    // 2. gossip to dead nodes
    if (oldUnavailableNodesSize > 0) {
      val probability: Double = oldUnavailableNodesSize / (oldAvailableNodesSize + 1)
      if (random.nextDouble() < probability) gossipToRandomNodeOf(oldUnavailableNodes)
    }

    // 3. gossip to a seed for facilitating partition healing
    if ((!gossipedToSeed || oldAvailableNodesSize < 1) && (seeds.head != remoteAddress)) {
      if (oldAvailableNodesSize == 0) gossipToRandomNodeOf(seeds)
      else {
        val probability = 1.0 / oldAvailableNodesSize + oldUnavailableNodesSize
        if (random.nextDouble() <= probability) gossipToRandomNodeOf(seeds)
      }
    }
  }

  /**
   * Gossips to a random node in the set of nodes passed in as argument.
   *
   * @returns 'true' if it gossiped to a "seed" node.
   */
  private def gossipToRandomNodeOf(nodes: Set[ParsedTransportAddress]): Boolean = {
    val peers = nodes filter (_ != remoteAddress) // filter out myself
    val peer = selectRandomNode(peers)
    val oldState = state.get
    val oldGossip = oldState.currentGossip

    setUpConnectionToNode(peer) match {
      case Some(connection) ⇒
        try {
          Await.result(connection ? newGossip, seedNodeConnectionTimeout) match {
            case Success(receiver) ⇒ log.debug("Gossip sent to [{}] was successfully received", receiver)
            case Failure(cause)    ⇒ log.error(cause, cause.toString)
          }
        } catch {
          case e: TimeoutException ⇒ log.error(e, "Gossip to [%s] timed out".format(connection.path))
          case e: Exception        ⇒ log.error(e, "Could not gossip to [{}] due to: {}", connection.path, e.toString)
        }
      case None ⇒
      // FIXME what to do if the node can't be reached for gossiping - mark as unavailable in failure detector?
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

  private def setUpConnectionToNode(node: ParsedTransportAddress): Option[ActorRef] = {
    //connectionManager.newConnection(node, RootActorPath(RemoteSystemAddress(system.name, node)) / "system" / "cluster")
    try {
      Some(
        connectionManager.putIfAbsent(
          node,
          () ⇒ system.actorFor(RootActorPath(RemoteSystemAddress(system.name, node)) / "system" / "cluster")))
      // connectionManager.connectionFor(node).getOrElse(
      //   throw new RemoteConnectionException("Could not set up connection to node [" + node + "]"))
    } catch {
      case e: Exception ⇒ None
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

  private def latestVersionOf(newGossip: Gossip, oldGossip: Gossip): Gossip = {
    (newGossip.version compare oldGossip.version) match {
      case VectorClock.After      ⇒ newGossip // gossiped version is newer, use new version
      case VectorClock.Before     ⇒ oldGossip // gossiped version is older, use old version
      case VectorClock.Concurrent ⇒ oldGossip // can't establish a causal relationship between two versions => conflict
    }
  }

  private def seedNodesWithoutMyself: List[Address] = seeds.filter(_ != remoteAddress.transport).toList

  private def selectRandomNode(nodes: Set[Address]): Address = {
    nodes.toList(random.nextInt(nodes.size))
  }
}
