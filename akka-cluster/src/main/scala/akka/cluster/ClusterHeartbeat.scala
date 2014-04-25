/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.{ ActorLogging, ActorRef, ActorSelection, Address, Actor, RootActorPath, Props }
import akka.cluster.ClusterEvent._
import akka.routing.MurmurHash
import akka.remote.FailureDetectorRegistry
import akka.remote.PriorityMessage

/**
 * INTERNAL API.
 *
 * Receives Heartbeat messages and replies.
 */
private[cluster] final class ClusterHeartbeatReceiver extends Actor with ActorLogging {
  import ClusterHeartbeatSender._

  val selfHeartbeatRsp = HeartbeatRsp(Cluster(context.system).selfUniqueAddress)

  def receive = {
    case Heartbeat(from) ⇒ sender() ! selfHeartbeatRsp
  }

}

/**
 * INTERNAL API
 */
private[cluster] object ClusterHeartbeatSender {
  /**
   * Sent at regular intervals for failure detection.
   */
  final case class Heartbeat(from: Address) extends ClusterMessage with PriorityMessage

  /**
   * Sent as reply to [[Heartbeat]] messages.
   */
  final case class HeartbeatRsp(from: UniqueAddress) extends ClusterMessage with PriorityMessage

  // sent to self only
  case object HeartbeatTick
  final case class ExpectedFirstHeartbeat(from: UniqueAddress)

}

/*
 * INTERNAL API
 *
 * This actor is responsible for sending the heartbeat messages to
 * a few other nodes, which will reply and then this actor updates the
 * failure detector.
 */
private[cluster] final class ClusterHeartbeatSender extends Actor with ActorLogging {
  import ClusterHeartbeatSender._

  val cluster = Cluster(context.system)
  import cluster.{ selfAddress, selfUniqueAddress, scheduler }
  import cluster.settings._
  import cluster.InfoLogger._
  import context.dispatcher

  // the failureDetector is only updated by this actor, but read from other places
  val failureDetector = Cluster(context.system).failureDetector

  val selfHeartbeat = Heartbeat(selfAddress)

  var state = ClusterHeartbeatSenderState(
    ring = HeartbeatNodeRing(selfUniqueAddress, Set(selfUniqueAddress), MonitoredByNrOfMembers),
    unreachable = Set.empty[UniqueAddress],
    failureDetector)

  // start periodic heartbeat to other nodes in cluster
  val heartbeatTask = scheduler.schedule(PeriodicTasksInitialDelay max HeartbeatInterval,
    HeartbeatInterval, self, HeartbeatTick)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    state.activeReceivers.foreach(a ⇒ failureDetector.remove(a.address))
    heartbeatTask.cancel()
    cluster.unsubscribe(self)
  }

  /**
   * Looks up and returns the remote cluster heartbeat connection for the specific address.
   */
  def heartbeatReceiver(address: Address): ActorSelection =
    context.actorSelection(RootActorPath(address) / "system" / "cluster" / "heartbeatReceiver")

  def receive = initializing

  def initializing: Actor.Receive = {
    case s: CurrentClusterState ⇒
      init(s)
      context.become(active)
    case HeartbeatTick ⇒
  }

  def active: Actor.Receive = {
    case HeartbeatTick                ⇒ heartbeat()
    case HeartbeatRsp(from)           ⇒ heartbeatRsp(from)
    case MemberUp(m)                  ⇒ addMember(m)
    case MemberRemoved(m, _)          ⇒ removeMember(m)
    case _: MemberEvent               ⇒ // not interested in other types of MemberEvent
    case ExpectedFirstHeartbeat(from) ⇒ triggerFirstHeartbeat(from)
  }

  def init(snapshot: CurrentClusterState): Unit = {
    val nodes: Set[UniqueAddress] = snapshot.members.collect {
      case m if m.status == MemberStatus.Up ⇒ m.uniqueAddress
    }(collection.breakOut)
    state = state.init(nodes)
  }

  def addMember(m: Member): Unit =
    if (m.uniqueAddress != selfUniqueAddress)
      state = state.addMember(m.uniqueAddress)

  def removeMember(m: Member): Unit =
    if (m.uniqueAddress == cluster.selfUniqueAddress) {
      // This cluster node will be shutdown, but stop this actor immediately
      // to avoid further updates
      context stop self
    } else {
      state = state.removeMember(m.uniqueAddress)
    }

  def heartbeat(): Unit = {
    state.activeReceivers foreach { to ⇒
      if (cluster.failureDetector.isMonitoring(to.address))
        log.debug("Cluster Node [{}] - Heartbeat to [{}]", selfAddress, to.address)
      else {
        log.debug("Cluster Node [{}] - First Heartbeat to [{}]", selfAddress, to.address)
        // schedule the expected first heartbeat for later, which will give the
        // other side a chance to reply, and also trigger some resends if needed
        scheduler.scheduleOnce(HeartbeatExpectedResponseAfter, self, ExpectedFirstHeartbeat(to))
      }
      heartbeatReceiver(to.address) ! selfHeartbeat
    }

  }

  def heartbeatRsp(from: UniqueAddress): Unit = {
    log.debug("Cluster Node [{}] - Heartbeat response from [{}]", selfAddress, from.address)
    state = state.heartbeatRsp(from)
  }

  def triggerFirstHeartbeat(from: UniqueAddress): Unit =
    if (state.activeReceivers(from) && !failureDetector.isMonitoring(from.address)) {
      log.debug("Cluster Node [{}] - Trigger extra expected heartbeat from [{}]", selfAddress, from.address)
      failureDetector.heartbeat(from.address)
    }

}

/**
 * INTERNAL API
 * State of [[ClusterHeartbeatSender]]. Encapsulated to facilitate unit testing.
 * It is immutable, but it updates the failureDetector.
 */
private[cluster] final case class ClusterHeartbeatSenderState(
  ring: HeartbeatNodeRing,
  unreachable: Set[UniqueAddress],
  failureDetector: FailureDetectorRegistry[Address]) {

  val activeReceivers: Set[UniqueAddress] = ring.myReceivers ++ unreachable

  def selfAddress = ring.selfAddress

  def init(nodes: Set[UniqueAddress]): ClusterHeartbeatSenderState =
    copy(ring = ring.copy(nodes = nodes + selfAddress))

  def addMember(node: UniqueAddress): ClusterHeartbeatSenderState =
    membershipChange(ring :+ node)

  def removeMember(node: UniqueAddress): ClusterHeartbeatSenderState = {
    val newState = membershipChange(ring :- node)

    failureDetector remove node.address
    if (newState.unreachable(node))
      newState.copy(unreachable = newState.unreachable - node)
    else
      newState
  }

  private def membershipChange(newRing: HeartbeatNodeRing): ClusterHeartbeatSenderState = {
    val oldReceivers = ring.myReceivers
    val removedReceivers = oldReceivers -- newRing.myReceivers
    var newUnreachable = unreachable
    removedReceivers foreach { a ⇒
      if (failureDetector.isAvailable(a.address))
        failureDetector remove a.address
      else
        newUnreachable += a
    }
    copy(newRing, newUnreachable)
  }

  def heartbeatRsp(from: UniqueAddress): ClusterHeartbeatSenderState =
    if (activeReceivers(from)) {
      failureDetector heartbeat from.address
      if (unreachable(from)) {
        // back from unreachable, ok to stop heartbeating to it
        if (!ring.myReceivers(from))
          failureDetector remove from.address
        copy(unreachable = unreachable - from)
      } else this
    } else this

}

/**
 * INTERNAL API
 *
 * Data structure for picking heartbeat receivers. The node ring is
 * shuffled by deterministic hashing to avoid picking physically co-located
 * neighbors.
 *
 * It is immutable, i.e. the methods return new instances.
 */
private[cluster] final case class HeartbeatNodeRing(selfAddress: UniqueAddress, nodes: Set[UniqueAddress], monitoredByNrOfMembers: Int) {

  require(nodes contains selfAddress, s"nodes [${nodes.mkString(", ")}] must contain selfAddress [${selfAddress}]")

  private val nodeRing: immutable.SortedSet[UniqueAddress] = {
    implicit val ringOrdering: Ordering[UniqueAddress] = Ordering.fromLessThan[UniqueAddress] { (a, b) ⇒
      val ha = a.##
      val hb = b.##
      ha < hb || (ha == hb && Member.addressOrdering.compare(a.address, b.address) < 0)
    }

    immutable.SortedSet() ++ nodes
  }

  /**
   * Receivers for `selfAddress`. Cached for subsequent access.
   */
  lazy val myReceivers: immutable.Set[UniqueAddress] = receivers(selfAddress)

  private val useAllAsReceivers = monitoredByNrOfMembers >= (nodeRing.size - 1)

  /**
   * The receivers to use from a specified sender.
   */
  def receivers(sender: UniqueAddress): immutable.Set[UniqueAddress] =
    if (useAllAsReceivers)
      nodeRing - sender
    else {
      val slice = nodeRing.from(sender).tail.take(monitoredByNrOfMembers)
      if (slice.size < monitoredByNrOfMembers)
        (slice ++ nodeRing.take(monitoredByNrOfMembers - slice.size))
      else slice
    }

  /**
   * Add a node to the ring.
   */
  def :+(node: UniqueAddress): HeartbeatNodeRing = if (nodes contains node) this else copy(nodes = nodes + node)

  /**
   * Remove a node from the ring.
   */
  def :-(node: UniqueAddress): HeartbeatNodeRing = if (nodes contains node) copy(nodes = nodes - node) else this

}
