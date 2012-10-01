/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import scala.annotation.tailrec
import scala.concurrent.util.duration._
import scala.concurrent.util.Deadline
import scala.concurrent.util.FiniteDuration
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
  val selfAddressStr = selfAddress.toString

  var all = Set.empty[Address]
  var current = Set.empty[Address]
  var ending = Map.empty[Address, Int]
  var joinInProgress = Map.empty[Address, Deadline]
  var consistentHash = ConsistentHash(Seq.empty[Address], HeartbeatConsistentHashingVirtualNodesFactor)

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask =
    FixedRateTask(scheduler, PeriodicTasksInitialDelay.max(HeartbeatInterval).asInstanceOf[FiniteDuration], HeartbeatInterval) {
      self ! HeartbeatTick
    }

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
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
    case HeartbeatTick              ⇒ heartbeat()
    case state: CurrentClusterState ⇒ init(state)
    case MemberUnreachable(m)       ⇒ removeMember(m)
    case MemberRemoved(m)           ⇒ removeMember(m)
    case e: MemberEvent             ⇒ addMember(e.member)
    case JoinInProgress(a, d)       ⇒ addJoinInProgress(a, d)
  }

  def init(state: CurrentClusterState): Unit = {
    all = state.members.collect { case m if m.address != selfAddress ⇒ m.address }
    joinInProgress --= all
    consistentHash = ConsistentHash(all, HeartbeatConsistentHashingVirtualNodesFactor)
  }

  def addMember(m: Member): Unit = if (m.address != selfAddress) {
    all += m.address
    consistentHash = consistentHash :+ m.address
    removeJoinInProgress(m.address)
    update()
  }

  def removeMember(m: Member): Unit = if (m.address != selfAddress) {
    all -= m.address
    consistentHash = consistentHash :- m.address
    removeJoinInProgress(m.address)
    update()
  }

  def removeJoinInProgress(address: Address): Unit = if (joinInProgress contains address) {
    joinInProgress -= address
    ending += (address -> 0)
  }

  def addJoinInProgress(address: Address, deadline: Deadline): Unit = {
    if (address != selfAddress && !all.contains(address))
      joinInProgress += (address -> deadline)
  }

  def heartbeat(): Unit = {
    removeOverdueJoinInProgress()

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
    (current ++ joinInProgress.keys) foreach { to ⇒ connection(to) ! SendHeartbeat(selfHeartbeat, to, deadline) }

    // When sending heartbeats to a node is stopped a few `EndHeartbeat` messages is
    // sent to notify it that no more heartbeats will be sent.
    for ((to, count) ← ending) {
      val c = connection(to)
      c ! SendEndHeartbeat(selfEndHeartbeat, to)
      if (count == NumberOfEndHeartbeats) {
        ending -= to
        c ! PoisonPill
      } else {
        ending += (to -> (count + 1))
      }
    }
  }

  /**
   * Update current peers to send heartbeats to, and
   * keep track of which nodes to stop sending heartbeats to.
   */
  def update(): Unit = {
    val previous = current
    current = selectPeers
    // start ending process for nodes not selected any more
    ending ++= (previous -- current).map(_ -> 0)
    // abort ending process for nodes that have been selected again
    ending --= current
  }

  /**
   * Select a few peers that heartbeats will be sent to, i.e. that will
   * monitor this node. Try to send heartbeats to same nodes as much
   * as possible, but re-balance with consistent hashing algorithm when
   * new members are added or removed.
   */
  def selectPeers: Set[Address] = {
    val allSize = all.size
    val nrOfPeers = math.min(allSize, MonitoredByNrOfMembers)
    // try more if consistentHash results in same node as already selected
    val attemptLimit = nrOfPeers * 2
    @tailrec def select(acc: Set[Address], n: Int): Set[Address] = {
      if (acc.size == nrOfPeers || n == attemptLimit) acc
      else select(acc + consistentHash.nodeFor(selfAddressStr + n), n + 1)
    }
    if (nrOfPeers >= allSize) all
    else select(Set.empty[Address], 0)
  }

  /**
   * Cleanup overdue joinInProgress, in case a joining node never
   * became member, for some reason.
   */
  def removeOverdueJoinInProgress(): Unit = {
    val overdue = joinInProgress collect { case (address, deadline) if deadline.isOverdue ⇒ address }
    if (overdue.nonEmpty) {
      log.info("Overdue join in progress [{}]", overdue.mkString(", "))
      ending ++= overdue.map(_ -> 0)
      joinInProgress --= overdue
    }
  }

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
 * Netty blocks when sending to broken connections, and this actor uses
 * a configurable circuit breaker to reduce connect attempts to broken
 * connections.
 *
 * @see ClusterHeartbeatSender
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
        // the CircuitBreaker will measure elapsed time and open if too many long calls
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