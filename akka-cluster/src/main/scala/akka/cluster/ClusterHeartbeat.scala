/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import scala.collection.immutable.SortedSet
import scala.annotation.tailrec
import scala.concurrent.duration._
import java.net.URLEncoder
import akka.actor.{ ActorLogging, ActorRef, Address, Actor, RootActorPath, PoisonPill, Props }
import akka.pattern.{ CircuitBreaker, CircuitBreakerOpenException }
import akka.cluster.ClusterEvent._
import akka.routing.ConsistentHash

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
   * Tell [akka.cluster.ClusterHeartbeatSender]] that this node has started joining of
   * another node and heartbeats should be sent unconditionally until it becomes
   * member or deadline is overdue. This is done to be able to detect immediate death
   * of the joining node.
   * Local only, no need to serialize.
   */
  case class JoinInProgress(address: Address, deadline: Deadline)
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

  var state = ClusterHeartbeatSenderState.empty(ConsistentHash(Seq.empty[Address], HeartbeatConsistentHashingVirtualNodesFactor),
    selfAddress.toString, MonitoredByNrOfMembers)

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask = scheduler.schedule(PeriodicTasksInitialDelay max HeartbeatInterval,
    HeartbeatInterval, self, HeartbeatTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
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

  def receive = {
    case HeartbeatTick          ⇒ heartbeat()
    case s: CurrentClusterState ⇒ reset(s)
    case UnreachableMember(m)   ⇒ removeMember(m)
    case MemberRemoved(m)       ⇒ removeMember(m)
    case e: MemberEvent         ⇒ addMember(e.member)
    case JoinInProgress(a, d)   ⇒ addJoinInProgress(a, d)
  }

  def reset(snapshot: CurrentClusterState): Unit =
    state = state.reset(snapshot.members.collect { case m if m.address != selfAddress ⇒ m.address })

  def addMember(m: Member): Unit = if (m.address != selfAddress)
    state = state addMember m.address

  def removeMember(m: Member): Unit = if (m.address != selfAddress)
    state = state removeMember m.address

  def addJoinInProgress(address: Address, deadline: Deadline): Unit = if (address != selfAddress)
    state = state.addJoinInProgress(address, deadline)

  def heartbeat(): Unit = {
    state = state.removeOverdueJoinInProgress()

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
  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSenderState {
  /**
   * Initial, empty state
   */
  def empty(consistentHash: ConsistentHash[Address], selfAddressStr: String,
            monitoredByNrOfMembers: Int): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(consistentHash, selfAddressStr, monitoredByNrOfMembers)

  /**
   * Create a new state based on previous state, and
   * keep track of which nodes to stop sending heartbeats to.
   */
  private def apply(
    old: ClusterHeartbeatSenderState,
    consistentHash: ConsistentHash[Address],
    all: Set[Address]): ClusterHeartbeatSenderState = {

    /**
     * Select a few peers that heartbeats will be sent to, i.e. that will
     * monitor this node. Try to send heartbeats to same nodes as much
     * as possible, but re-balance with consistent hashing algorithm when
     * new members are added or removed.
     */
    def selectPeers: Set[Address] = {
      val allSize = all.size
      val nrOfPeers = math.min(allSize, old.monitoredByNrOfMembers)
      // try more if consistentHash results in same node as already selected
      val attemptLimit = nrOfPeers * 2
      @tailrec def select(acc: Set[Address], n: Int): Set[Address] = {
        if (acc.size == nrOfPeers || n == attemptLimit) acc
        else select(acc + consistentHash.nodeFor(old.selfAddressStr + n), n + 1)
      }
      if (nrOfPeers >= allSize) all
      else select(Set.empty[Address], 0)
    }

    val curr = selectPeers
    // start ending process for nodes not selected any more
    // abort ending process for nodes that have been selected again
    val end = old.ending ++ (old.current -- curr).map(_ -> 0) -- curr
    old.copy(consistentHash = consistentHash, all = all, current = curr, ending = end)
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
  consistentHash: ConsistentHash[Address],
  selfAddressStr: String,
  monitoredByNrOfMembers: Int,
  all: Set[Address] = Set.empty,
  current: Set[Address] = Set.empty,
  ending: Map[Address, Int] = Map.empty,
  joinInProgress: Map[Address, Deadline] = Map.empty) {

  // FIXME can be disabled as optimization
  assertInvariants

  private def assertInvariants: Unit = {
    val currentAndEnding = current.intersect(ending.keySet)
    require(currentAndEnding.isEmpty,
      "Same nodes in current and ending not allowed, got [%s]" format currentAndEnding)
    val joinInProgressAndAll = joinInProgress.keySet.intersect(all)
    require(joinInProgressAndAll.isEmpty,
      "Same nodes in joinInProgress and all not allowed, got [%s]" format joinInProgressAndAll)
    val currentNotInAll = current -- all
    require(currentNotInAll.isEmpty,
      "Nodes in current but not in all not allowed, got [%s]" format currentNotInAll)
    require(all.isEmpty == consistentHash.isEmpty, "ConsistentHash doesn't correspond to all nodes [%s]"
      format all)
  }

  val active: Set[Address] = current ++ joinInProgress.keySet

  def reset(nodes: Set[Address]): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(nodes.foldLeft(this) { _ removeJoinInProgress _ },
      consistentHash = ConsistentHash(nodes, consistentHash.virtualNodesFactor),
      all = nodes)

  def addMember(a: Address): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(removeJoinInProgress(a), all = all + a, consistentHash = consistentHash :+ a)

  def removeMember(a: Address): ClusterHeartbeatSenderState =
    ClusterHeartbeatSenderState(removeJoinInProgress(a), all = all - a, consistentHash = consistentHash :- a)

  private def removeJoinInProgress(address: Address): ClusterHeartbeatSenderState = {
    if (joinInProgress contains address)
      copy(joinInProgress = joinInProgress - address, ending = ending + (address -> 0))
    else this
  }

  def addJoinInProgress(address: Address, deadline: Deadline): ClusterHeartbeatSenderState = {
    if (all contains address) this
    else copy(joinInProgress = joinInProgress + (address -> deadline), ending = ending - address)
  }

  /**
   * Cleanup overdue joinInProgress, in case a joining node never
   * became member, for some reason.
   */
  def removeOverdueJoinInProgress(): ClusterHeartbeatSenderState = {
    val overdue = joinInProgress collect { case (address, deadline) if deadline.isOverdue ⇒ address }
    if (overdue.isEmpty) this
    else
      copy(ending = ending ++ overdue.map(_ -> 0), joinInProgress = joinInProgress -- overdue)
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
      onOpen(log.debug("CircuitBreaker Open for [{}]", toRef)).
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
      if (deadline.isOverdue) log.debug("Sending heartbeat to [{}] took longer than expected", toRef)
    case SendEndHeartbeat(endHeartbeatMsg, _) ⇒
      log.debug("Cluster Node [{}] - EndHeartbeat to [{}]", endHeartbeatMsg.from, toRef)
      toRef ! endHeartbeatMsg
  }
}
