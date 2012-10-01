/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
import akka.actor.{ ReceiveTimeout, ActorLogging, ActorRef, Address, Actor, RootActorPath, Props }
import java.security.MessageDigest
import akka.pattern.{ CircuitBreaker, CircuitBreakerOpenException }
import scala.concurrent.util.duration._
import scala.concurrent.util.Deadline
import scala.concurrent.util.FiniteDuration
import akka.cluster.ClusterEvent._

/**
 * Sent at regular intervals for failure detection.
 */
case class Heartbeat(from: Address) extends ClusterMessage

/**
 * INTERNAL API.
 *
 * Receives Heartbeat messages and updates failure detector.
 * Instantiated as a single instance for each Cluster - e.g. heartbeats are serialized
 * to Cluster message after message, but concurrent with other types of messages.
 */
private[cluster] final class ClusterHeartbeatReceiver extends Actor with ActorLogging {

  val failureDetector = Cluster(context.system).failureDetector

  def receive = {
    case Heartbeat(from) ⇒ failureDetector heartbeat from
  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSender {
  /**
   * Command to [akka.cluster.ClusterHeartbeatSenderWorker]], which will send [[akka.cluster.Heartbeat]]
   * to the other node.
   * Local only, no need to serialize.
   */
  case class SendHeartbeat(heartbeatMsg: Heartbeat, to: Address, deadline: Deadline)

  /**
   * Tell [akka.cluster.ClusterHeartbeatSender]] that this node has started joining of
   * another node and heartbeats should be sent until it becomes member or deadline is overdue.
   * Local only, no need to serialize.
   */
  case class JoinInProgress(address: Address, deadline: Deadline)
}

/*
 * INTERNAL API
 *
 * This actor is responsible for sending the heartbeat messages to
 * other nodes. Netty blocks when sending to broken connections. This actor
 * isolates sending to different nodes by using child workers for each target
 * address and thereby reduce the risk of irregular heartbeats to healty
 * nodes due to broken connections to other nodes.
 */
private[cluster] final class ClusterHeartbeatSender extends Actor with ActorLogging {
  import ClusterHeartbeatSender._
  import Member.addressOrdering
  import InternalClusterAction.HeartbeatTick

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, scheduler }
  import cluster.settings._
  import context.dispatcher

  val selfHeartbeat = Heartbeat(selfAddress)

  var nodes: SortedSet[Address] = SortedSet.empty
  var joinInProgress: Map[Address, Deadline] = Map.empty

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

  val digester = MessageDigest.getInstance("MD5")

  /**
   * Child name is MD5 hash of the address.
   * FIXME Change to URLEncode when ticket #2123 has been fixed
   */
  def encodeChildName(name: String): String = {
    digester update name.getBytes("UTF-8")
    digester.digest.map { h ⇒ "%02x".format(0xFF & h) }.mkString
  }

  def receive = {
    case state: CurrentClusterState ⇒ init(state)
    case MemberUnreachable(m)       ⇒ removeMember(m)
    case MemberRemoved(m)           ⇒ removeMember(m)
    case e: MemberEvent             ⇒ addMember(e.member)
    case JoinInProgress(a, d)       ⇒ joinInProgress += (a -> d)
    case HeartbeatTick              ⇒ heartbeat()
  }

  def init(state: CurrentClusterState): Unit = {
    nodes = state.members.map(_.address)
    joinInProgress --= nodes
  }

  def addMember(m: Member): Unit = {
    nodes += m.address
    joinInProgress -= m.address
  }

  def removeMember(m: Member): Unit = {
    nodes -= m.address
    joinInProgress -= m.address
  }

  def heartbeat(): Unit = {
    removeOverdueJoinInProgress()

    val beatTo = nodes ++ joinInProgress.keys

    val deadline = Deadline.now + HeartbeatInterval
    for (to ← beatTo; if to != selfAddress) {
      val workerName = encodeChildName(to.toString)
      val worker = context.actorFor(workerName) match {
        case notFound if notFound.isTerminated ⇒
          context.actorOf(Props(new ClusterHeartbeatSenderWorker(clusterHeartbeatConnectionFor(to))), workerName)
        case child ⇒ child
      }
      worker ! SendHeartbeat(selfHeartbeat, to, deadline)
    }
  }

  /**
   * Removes overdue joinInProgress from State.
   */
  def removeOverdueJoinInProgress(): Unit = {
    joinInProgress --= joinInProgress collect { case (address, deadline) if (nodes contains address) || deadline.isOverdue ⇒ address }
  }

}

/**
 * Responsible for sending [[akka.cluster.Heartbeat]] to one specific address.
 *
 * Netty blocks when sending to broken connections, and this actor uses
 * a configurable circuit breaker to reduce connect attempts to broken
 * connections.
 *
 * @see ClusterHeartbeatSender
 */
private[cluster] final class ClusterHeartbeatSenderWorker(toRef: ActorRef)
  extends Actor with ActorLogging {

  import ClusterHeartbeatSender._

  val breaker = {
    val cbSettings = Cluster(context.system).settings.SendCircuitBreakerSettings
    CircuitBreaker(context.system.scheduler,
      cbSettings.maxFailures, cbSettings.callTimeout, cbSettings.resetTimeout).
      onHalfOpen(log.debug("CircuitBreaker Half-Open for: [{}]", toRef)).
      onOpen(log.debug("CircuitBreaker Open for [{}]", toRef)).
      onClose(log.debug("CircuitBreaker Closed for [{}]", toRef))
  }

  // make sure it will cleanup when not used any more
  context.setReceiveTimeout(30 seconds)

  def receive = {
    case SendHeartbeat(heartbeatMsg, _, deadline) ⇒
      if (!deadline.isOverdue) {
        // the CircuitBreaker will measure elapsed time and open if too many long calls
        try breaker.withSyncCircuitBreaker {
          log.debug("Cluster Node [{}] - Heartbeat to [{}]", heartbeatMsg.from, toRef)
          toRef ! heartbeatMsg
          if (deadline.isOverdue) log.debug("Sending heartbeat to [{}] took longer than expected", toRef)
        } catch { case e: CircuitBreakerOpenException ⇒ /* skip sending heartbeat to broken connection */ }
      }

    case ReceiveTimeout ⇒ context.stop(self) // cleanup when not used

  }
}