/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import java.net.URLEncoder
import akka.actor.{ ActorLogging, ActorRef, Address, Actor, RootActorPath, PoisonPill, Props }
import akka.pattern.{ CircuitBreaker, CircuitBreakerOpenException }
import akka.cluster.ClusterEvent._
import akka.routing.MurmurHash

/**
 * INTERNAL API
 */
private[akka] object ClusterHeartbeatReceiver {
  /**
   * Sent at regular intervals for failure detection.
   */
  case class Heartbeat(from: Address) extends ClusterMessage

  /**
   * Tell failure detector at receiving side that it should
   * remove the monitoring, because heartbeats will end from
   * this node.
   */
  case class EndHeartbeat(from: Address) extends ClusterMessage
}

/**
 * INTERNAL API.
 *
 * Receives Heartbeat messages and updates failure detector.
 * Instantiated as a single instance for each Cluster - e.g. heartbeats are serialized
 * to Cluster message after message, but concurrent with other types of messages.
 */
private[cluster] final class ClusterHeartbeatReceiver extends Actor with ActorLogging {
  import ClusterHeartbeatReceiver._

  val failureDetector = Cluster(context.system).failureDetector

  def receive = {
    case Heartbeat(from)    ⇒ failureDetector heartbeat from
    case EndHeartbeat(from) ⇒ failureDetector remove from
  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSender {
  /**
   * Request heartbeats from another node. Sent from the node that is
   * expecting heartbeats from a specific sender, but has not received any.
   */
  case class HeartbeatRequest(from: Address) extends ClusterMessage

  /**
   * Delayed sending of a HeartbeatRequest. The actual request is
   * only sent if no expected heartbeat message has been received.
   * Local only, no need to serialize.
   */
  case class SendHeartbeatRequest(to: Address)

  /**
   * Trigger a fake heartbeat message to trigger start of failure detection
   * of a node that this node is expecting heartbeats from. HeartbeatRequest
   * has been sent to the node so it should have started sending heartbeat
   * messages.
   * Local only, no need to serialize.
   */
  case class ExpectedFirstHeartbeat(from: Address)
}

/*
 * INTERNAL API
 *
 * This actor is responsible for sending the heartbeat messages to
 * a few other nodes that will monitor this node.
 *
 * Netty blocks when sending to broken connections. This actor
 * isolates sending to different nodes by using child actors for each target
 * address and thereby reduce the risk of irregular heartbeats to healty
 * nodes due to broken connections to other nodes.
 */
private[cluster] final class ClusterHeartbeatSender extends Actor with ActorLogging {
  import ClusterHeartbeatSender._
  import ClusterHeartbeatSenderConnection._
  import ClusterHeartbeatReceiver._
  import InternalClusterAction.HeartbeatTick

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, scheduler }
  import cluster.settings._
  import context.dispatcher

  val selfHeartbeat = Heartbeat(selfAddress)
  val selfEndHeartbeat = EndHeartbeat(selfAddress)
  val selfHeartbeatRequest = HeartbeatRequest(selfAddress)

  var state = ClusterHeartbeatSenderState.empty(selfAddress, MonitoredByNrOfMembers)

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask = scheduler.schedule(PeriodicTasksInitialDelay max HeartbeatInterval,
    HeartbeatInterval, self, HeartbeatTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[InstantMemberEvent])
    cluster.subscribe(self, classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    heartbeatTask.cancel()
    cluster.unsubscribe(self)
  }

  /**
   * Looks up and returns the remote cluster heartbeat connection for the specific address.
   */
  def clusterHeartbeatConnectionFor(address: Address): ActorRef =
    context.actorFor(RootActorPath(address) / "system" / "cluster" / "heartbeatReceiver")

  /**
   * Looks up and returns the remote cluster heartbeat sender for the specific address.
   */
  def heartbeatSenderFor(address: Address): ActorRef = context.actorFor(self.path.toStringWithAddress(address))

  def receive = {
    case HeartbeatTick                ⇒ heartbeat()
    case InstantMemberUp(m)           ⇒ addMember(m)
    case UnreachableMember(m)         ⇒ removeMember(m)
    case InstantMemberDowned(m)       ⇒ removeMember(m)
    case InstantMemberRemoved(m)      ⇒ removeMember(m)
    case s: InstantClusterState       ⇒ reset(s)
    case _: CurrentClusterState       ⇒ // enough with InstantClusterState
    case _: InstantMemberEvent        ⇒ // not interested in other types of InstantMemberEvent
    case HeartbeatRequest(from)       ⇒ addHeartbeatRequest(from)
    case SendHeartbeatRequest(to)     ⇒ sendHeartbeatRequest(to)
    case ExpectedFirstHeartbeat(from) ⇒ triggerFirstHeartbeat(from)
  }

  def reset(snapshot: InstantClusterState): Unit =
    state = state.reset(snapshot.members.map(_.address))

  def addMember(m: Member): Unit = if (m.address != selfAddress)
    state = state addMember m.address

  def removeMember(m: Member): Unit = if (m.address != selfAddress)
    state = state removeMember m.address

  def addHeartbeatRequest(address: Address): Unit = if (address != selfAddress)
    state = state.addHeartbeatRequest(address, Deadline.now + HeartbeatRequestTimeToLive)

  def sendHeartbeatRequest(address: Address): Unit =
    if (!cluster.failureDetector.isMonitoring(address) && state.ring.mySenders.contains(address)) {
      heartbeatSenderFor(address) ! selfHeartbeatRequest
      // schedule the expected heartbeat for later, which will give the
      // sender a chance to start heartbeating, and also trigger some resends of
      // the heartbeat request
      scheduler.scheduleOnce(HeartbeatExpectedResponseAfter, self, ExpectedFirstHeartbeat(address))
    }

  def triggerFirstHeartbeat(address: Address): Unit =
    if (!cluster.failureDetector.isMonitoring(address)) {
      log.info("Trigger extra expected heartbeat from [{}]", address)
      cluster.failureDetector.heartbeat(address)
    }

  def heartbeat(): Unit = {
    state = state.removeOverdueHeartbeatRequest()

    def connection(to: Address): ActorRef = {
      // URL encoded target address as child actor name
      val connectionName = URLEncoder.encode(to.toString, "UTF-8")
      context.actorFor(connectionName) match {
        case notFound if notFound.isTerminated ⇒
          context.actorOf(Props(new ClusterHeartbeatSenderConnection(clusterHeartbeatConnectionFor(to))), connectionName)
        case child ⇒ child
      }
    }

    val deadline = Deadline.now + HeartbeatInterval
    state.active foreach { to ⇒ connection(to) ! SendHeartbeat(selfHeartbeat, to, deadline) }

    // When sending heartbeats to a node is stopped a few `EndHeartbeat` messages is
    // sent to notify it that no more heartbeats will be sent.
    for ((to, count) ← state.ending) {
      val c = connection(to)
      c ! SendEndHeartbeat(selfEndHeartbeat, to)
      if (count == NumberOfEndHeartbeats) {
        state = state.removeEnding(to)
        c ! PoisonPill
      } else
        state = state.increaseEndingCount(to)
    }

    // request heartbeats from expected sender node if no heartbeat messages has been received
    state.ring.mySenders foreach { address ⇒
      if (!cluster.failureDetector.isMonitoring(address))
        scheduler.scheduleOnce(HeartbeatRequestDelay, self, SendHeartbeatRequest(address))
    }

  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSenderState {
  /**
   * Initial, empty state
   */
  def empty(selfAddress: Address, monitoredByNrOfMembers: Int): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(HeartbeatNodeRing(selfAddress, Set(selfAddress), monitoredByNrOfMembers))

  /**
   * Create a new state based on previous state, and
   * keep track of which nodes to stop sending heartbeats to.
   */
  private def apply(
    old: ClusterHeartbeatSenderState,
    ring: HeartbeatNodeRing): ClusterHeartbeatSenderState = {

    val curr = ring.myReceivers
    // start ending process for nodes not selected any more
    // abort ending process for nodes that have been selected again
    val end = old.ending ++ (old.current -- curr).map(_ -> 0) -- curr
    old.copy(ring = ring, current = curr, ending = end, heartbeatRequest = old.heartbeatRequest -- curr)
  }

}

/**
 * INTERNAL API
 *
 * State used by [akka.cluster.ClusterHeartbeatSender].
 * The initial state is created with `empty` in the of
 * the companion object, thereafter the state is modified
 * with the methods, such as `addMember`. It is immutable,
 * i.e. the methods return new instances.
 */
private[cluster] case class ClusterHeartbeatSenderState private (
  ring: HeartbeatNodeRing,
  current: Set[Address] = Set.empty,
  ending: Map[Address, Int] = Map.empty,
  heartbeatRequest: Map[Address, Deadline] = Map.empty) {

  // FIXME can be disabled as optimization
  assertInvariants

  private def assertInvariants: Unit = {
    val currentAndEnding = current.intersect(ending.keySet)
    require(currentAndEnding.isEmpty,
      s"Same nodes in current and ending not allowed, got [${currentAndEnding}]")

    val currentAndHeartbeatRequest = current.intersect(heartbeatRequest.keySet)
    require(currentAndHeartbeatRequest.isEmpty,
      s"Same nodes in current and heartbeatRequest not allowed, got [${currentAndHeartbeatRequest}]")

    val currentNotInAll = current -- ring.nodes
    require(current.isEmpty || currentNotInAll.isEmpty,
      s"Nodes in current but not in ring nodes not allowed, got [${currentNotInAll}]")

    require(!current.contains(ring.selfAddress),
      s"Self in current not allowed, got [${ring.selfAddress}]")
    require(!heartbeatRequest.contains(ring.selfAddress),
      s"Self in heartbeatRequest not allowed, got [${ring.selfAddress}]")
  }

  val active: Set[Address] = current ++ heartbeatRequest.keySet

  def reset(nodes: Set[Address]): ClusterHeartbeatSenderState = {
    ClusterHeartbeatSenderState(nodes.foldLeft(this) { _ removeHeartbeatRequest _ }, ring.copy(nodes = nodes + ring.selfAddress))
  }

  def addMember(a: Address): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(removeHeartbeatRequest(a), ring :+ a)

  def removeMember(a: Address): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(removeHeartbeatRequest(a), ring :- a)

  private def removeHeartbeatRequest(address: Address): ClusterHeartbeatSenderState = {
    if (heartbeatRequest contains address)
      copy(heartbeatRequest = heartbeatRequest - address, ending = ending + (address -> 0))
    else this
  }

  def addHeartbeatRequest(address: Address, deadline: Deadline): ClusterHeartbeatSenderState = {
    if (current.contains(address)) this
    else copy(heartbeatRequest = heartbeatRequest + (address -> deadline), ending = ending - address)
  }

  /**
   * Cleanup overdue heartbeatRequest
   */
  def removeOverdueHeartbeatRequest(): ClusterHeartbeatSenderState = {
    val overdue = heartbeatRequest collect { case (address, deadline) if deadline.isOverdue ⇒ address }
    if (overdue.isEmpty) this
    else
      copy(ending = ending ++ overdue.map(_ -> 0), heartbeatRequest = heartbeatRequest -- overdue)
  }

  def removeEnding(a: Address): ClusterHeartbeatSenderState = copy(ending = ending - a)

  def increaseEndingCount(a: Address): ClusterHeartbeatSenderState = copy(ending = ending + (a -> (ending(a) + 1)))

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSenderConnection {
  import ClusterHeartbeatReceiver._

  /**
   * Command to [akka.cluster.ClusterHeartbeatSenderConnection]], which will send
   * [[akka.cluster.ClusterHeartbeatReceiver.Heartbeat]] to the other node.
   * Local only, no need to serialize.
   */
  case class SendHeartbeat(heartbeatMsg: Heartbeat, to: Address, deadline: Deadline)

  /**
   * Command to [akka.cluster.ClusterHeartbeatSenderConnection]], which will send
   * [[akka.cluster.ClusterHeartbeatReceiver.EndHeartbeat]] to the other node.
   * Local only, no need to serialize.
   */
  case class SendEndHeartbeat(endHeartbeatMsg: EndHeartbeat, to: Address)
}

/**
 * Responsible for sending [[akka.cluster.ClusterHeartbeatReceiver.Heartbeat]]
 * and [[akka.cluster.ClusterHeartbeatReceiver.EndHeartbeat]] to one specific address.
 *
 * This actor exists only because Netty blocks when sending to broken connections,
 * and this actor uses a configurable circuit breaker to reduce connect attempts to broken
 * connections.
 *
 * @see akka.cluster.ClusterHeartbeatSender
 */
private[cluster] final class ClusterHeartbeatSenderConnection(toRef: ActorRef)
  extends Actor with ActorLogging {

  import ClusterHeartbeatSenderConnection._

  val breaker = {
    val cbSettings = Cluster(context.system).settings.SendCircuitBreakerSettings
    CircuitBreaker(context.system.scheduler,
      cbSettings.maxFailures, cbSettings.callTimeout, cbSettings.resetTimeout).
      onHalfOpen(log.debug("CircuitBreaker Half-Open for: [{}]", toRef)).
      onOpen(log.info("CircuitBreaker Open for [{}]", toRef)).
      onClose(log.debug("CircuitBreaker Closed for [{}]", toRef))
  }

  def receive = {
    case SendHeartbeat(heartbeatMsg, _, deadline) ⇒
      if (!deadline.isOverdue) {
        log.debug("Cluster Node [{}] - Heartbeat to [{}]", heartbeatMsg.from, toRef)
        // Netty blocks when sending to broken connections, the CircuitBreaker will
        // measure elapsed time and open if too many long calls
        try breaker.withSyncCircuitBreaker {
          toRef ! heartbeatMsg
        } catch { case e: CircuitBreakerOpenException ⇒ /* skip sending heartbeat to broken connection */ }
      }
      if (deadline.isOverdue) log.info("Sending heartbeat to [{}] took longer than expected", toRef)
    case SendEndHeartbeat(endHeartbeatMsg, _) ⇒
      log.debug("Cluster Node [{}] - EndHeartbeat to [{}]", endHeartbeatMsg.from, toRef)
      toRef ! endHeartbeatMsg
  }
}

/**
 * INTERNAL API
 *
 * Data structure for picking heartbeat receivers and keep track of what nodes
 * that are expected to send heartbeat messages to a node. The node ring is
 * shuffled by deterministic hashing to avoid picking physically co-located
 * neighbors.
 *
 * It is immutable, i.e. the methods return new instances.
 */
private[cluster] case class HeartbeatNodeRing(selfAddress: Address, nodes: Set[Address], monitoredByNrOfMembers: Int) {

  require(nodes contains selfAddress, s"nodes [${nodes.mkString(", ")}] must contain selfAddress [${selfAddress}]")

  private val nodeRing: SortedSet[Address] = {
    implicit val ringOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
      val ha = hashFor(a)
      val hb = hashFor(b)
      ha < hb || (ha == hb && Member.addressOrdering.compare(a, b) < 0)
    }

    SortedSet() ++ nodes
  }

  private def hashFor(node: Address): Int = node match {
    // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
    case Address(_, _, Some(host), Some(port)) ⇒ MurmurHash.stringHash(s"${host}:${port}")
    case _                                     ⇒ 0
  }

  /**
   * Receivers for `selfAddress`. Cached for subsequent access.
   */
  lazy val myReceivers: Set[Address] = receivers(selfAddress)
  /**
   * Senders for `selfAddress`. Cached for subsequent access.
   */
  lazy val mySenders: Set[Address] = senders(selfAddress)

  private val useAllAsReceivers = monitoredByNrOfMembers >= (nodeRing.size - 1)

  /**
   * The receivers to use from a specified sender.
   */
  def receivers(sender: Address): Set[Address] =
    if (useAllAsReceivers)
      nodeRing - sender
    else {
      val slice = nodeRing.from(sender).tail.take(monitoredByNrOfMembers)
      if (slice.size < monitoredByNrOfMembers)
        (slice ++ nodeRing.take(monitoredByNrOfMembers - slice.size))
      else slice
    }

  /**
   * The expected senders for a specific receiver.
   */
  def senders(receiver: Address): Set[Address] =
    nodes filter { sender ⇒ receivers(sender) contains receiver }

  /**
   * Add a node to the ring.
   */
  def :+(node: Address): HeartbeatNodeRing = if (nodes contains node) this else copy(nodes = nodes + node)

  /**
   * Remove a node from the ring.
   */
  def :-(node: Address): HeartbeatNodeRing = if (nodes contains node) copy(nodes = nodes - node) else this

}
