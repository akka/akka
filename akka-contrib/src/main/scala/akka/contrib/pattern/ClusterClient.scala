/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import scala.collection.immutable
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.ReceiveTimeout
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.routing.ConsistentHash
import akka.routing.MurmurHash

/**
 * INTERNAL API
 */
private object ClusterClient {
  case object PingTick
  case object RefreshContactsTick
}

/**
 * This actor is intended to be used on an external node that is not member
 * of the cluster. It acts like a gateway for sending messages to actors
 * somewhere in the cluster. From the initial contact points it will establish
 * a connection to a [[ClusterReceptionist]] somewhere in the cluster. It will
 * monitor the connection to the receptionist and establish a new connection if
 * the link goes down. When looking for a new receptionist it uses fresh contact
 * points retrieved from previous establishment, or periodically refreshed
 * contacts, i.e. not necessarily the initial contact points.
 *
 * You can send messages via the `ClusterClient` to any actor in the cluster
 * that is registered in the [[DistributedPubSubMediator]] used by the [[ClusterReceptionist]].
 * Messages are wrapped in [[DistributedPubSubMediator.Send]], [[DistributedPubSubMediator.SendToAll]]
 * or [[DistributedPubSubMediator.Publish]] with the semantics described in
 * [[DistributedPubSubMediator]].
 */
class ClusterClient(
  initialContacts: Set[ActorRef],
  pingInterval: FiniteDuration = 3.second,
  refreshContactsInterval: FiniteDuration = 1.minute)
  extends Actor with ActorLogging {

  import ClusterClient._
  import ClusterReceptionist._
  import DistributedPubSubMediator.{ Send, SendToAll, Publish }

  // FIXME use ActorSelection for initialContacts when that is in master
  initialContacts foreach { _ ! GetContacts }
  var contacts: immutable.IndexedSeq[ActorRef] = Vector.empty

  import context.dispatcher
  val refreshContactsTask = context.system.scheduler.schedule(
    refreshContactsInterval, refreshContactsInterval, self, RefreshContactsTick)
  val pingTask = context.system.scheduler.schedule(pingInterval, pingInterval, self, PingTick)
  context.setReceiveTimeout(pingInterval * 3)

  override def postStop: Unit = {
    refreshContactsTask.cancel()
    pingTask.cancel()
  }

  def receive = establishing

  def establishing: Actor.Receive = {
    case Contacts(contactPoints) ⇒
      if (contactPoints.nonEmpty) {
        contacts = contactPoints
        contacts foreach { _ ! Identify(None) }
      }
    case ActorIdentity(_, Some(receptionist)) ⇒
      context watch receptionist
      log.info("Connected to [{}]", receptionist.path)
      context.become(active(receptionist))
    case ActorIdentity(_, None) ⇒ // ok, use another instead
    case PingTick               ⇒
    case Pong                   ⇒
    case RefreshContactsTick    ⇒
    case ReceiveTimeout         ⇒ sendGetContacts()
    case msg                    ⇒ context.system.deadLetters forward msg
  }

  def active(receptionist: ActorRef): Actor.Receive = {
    def becomeEstablishing(): Unit = {
      log.info("Lost contact with [{}], restablishing connection", receptionist)
      sendGetContacts()
      context.become(establishing)
    }

    var pongTimestamp = System.nanoTime

    {
      case msg @ (_: Send | _: SendToAll | _: Publish) ⇒
        receptionist forward msg
      case PingTick ⇒
        if (System.nanoTime - pongTimestamp > 3 * pingInterval.toNanos)
          becomeEstablishing()
        else
          receptionist ! Ping
      case Pong ⇒
        pongTimestamp = System.nanoTime
      case RefreshContactsTick ⇒
        receptionist ! GetContacts
      case ReceiveTimeout ⇒
        becomeEstablishing()
      case Terminated(`receptionist`) ⇒
        becomeEstablishing()
      case Contacts(contactPoints) ⇒
        // refresh of contacts
        if (contactPoints.nonEmpty)
          contacts = contactPoints
      case _: ActorIdentity ⇒ // ok, from previous establish, already handled
    }
  }

  def sendGetContacts(): Unit = {
    if (contacts.isEmpty) initialContacts foreach { _ ! GetContacts }
    else if (contacts.size == 1) (initialContacts ++ contacts) foreach { _ ! GetContacts }
    else contacts foreach { _ ! GetContacts }
  }
}

/**
 * INTERNAL API
 */
private[pattern] object ClusterReceptionist {
  case object GetContacts
  case class Contacts(contactPoints: immutable.IndexedSeq[ActorRef])
  case object Ping
  case object Pong

  // FIXME change to akka.actor.Identify when that is in master
  case class Identify(messageId: Any)
  case class ActorIdentity(correlationId: Any, ref: Option[ActorRef])

  def roleOption(role: String): Option[String] = role match {
    case null | "" ⇒ None
    case _         ⇒ Some(role)
  }

}

/**
 * [[ClusterClient]] connects to this actor to retrieve. The receptionist forwards
 * messages from the client to the associated [[DistributedPubSubMediator]], i.e.
 * the client can send messages to any actor in the cluster that is registered in the
 * `DistributedPubSubMediator`. Messages from the client are wrapped in
 * [[DistributedPubSubMediator.Send]], [[DistributedPubSubMediator.SendToAll]]
 * or [[DistributedPubSubMediator.Publish]] with the semantics described in
 * [[DistributedPubSubMediator]].
 *
 * The `ClusterReceptionist` is supposed to be started on all nodes,
 * or all nodes with specified role, in the cluster.
 */
class ClusterReceptionist(
  pubSubMediator: ActorRef,
  role: Option[String],
  numberOfContacts: Int = 3)
  extends Actor with ActorLogging {

  import DistributedPubSubMediator.{ Send, SendToAll, Publish }

  /**
   * Java API constructor with default values.
   */
  def this(pubSubMediator: ActorRef, role: String) =
    this(pubSubMediator, ClusterReceptionist.roleOption(role))

  import ClusterReceptionist._

  val cluster = Cluster(context.system)
  import cluster.selfAddress

  role match {
    case None ⇒
    case Some(r) ⇒ require(cluster.selfRoles.contains(r),
      s"This cluster member [${selfAddress}] doesn't have the role [$role]")
  }

  var nodes: Set[Address] = Set.empty
  val virtualNodesFactor = 10
  var consistentHash: ConsistentHash[Address] = ConsistentHash(nodes, virtualNodesFactor)

  override def preStart(): Unit = {
    require(!cluster.isTerminated, "Cluster node must not be terminated")
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop: Unit = {
    cluster unsubscribe self
  }

  def matchingRole(m: Member): Boolean = role match {
    case None    ⇒ true
    case Some(r) ⇒ m.hasRole(r)
  }

  def receive = {
    case msg @ (_: Send | _: SendToAll | _: Publish) ⇒
      pubSubMediator forward msg

    case Ping ⇒
      sender ! Pong

    // FIXME remove when akka.actor.Identify when is in master
    case Identify(messageId) ⇒
      sender ! ActorIdentity(messageId, Some(self))

    case GetContacts ⇒
      // Consistent hashing is used to ensure that the reply to GetContacts
      // is the same from all nodes (most of the time) and it also
      // load balances the client connections among the nodes in the cluster.
      val ring = nodeRing
      if (numberOfContacts >= ring.size) {
        sender ! Contacts(ring.map(a ⇒ context.actorFor(self.path.toStringWithAddress(a)))(collection.breakOut))
      } else {
        val a = consistentHash.nodeFor(sender.path.toString)
        val slice = ring.from(a).tail.take(numberOfContacts)
        if (slice.size < numberOfContacts)
          (slice ++ nodeRing.take(numberOfContacts - slice.size))
        else slice
        sender ! Contacts(ring.map(a ⇒ context.actorFor(self.path.toStringWithAddress(a)))(collection.breakOut))
      }

    case state: CurrentClusterState ⇒
      nodes = state.members.collect { case m if m.status != MemberStatus.Joining && matchingRole(m) ⇒ m.address }
      consistentHash = ConsistentHash(nodes, virtualNodesFactor)

    case MemberUp(m) ⇒
      if (matchingRole(m)) {
        nodes += m.address
        consistentHash = ConsistentHash(nodes, virtualNodesFactor)
      }

    case MemberRemoved(m) ⇒
      if (m.address == selfAddress)
        context stop self
      else if (matchingRole(m)) {
        nodes -= m.address
        consistentHash = ConsistentHash(nodes, virtualNodesFactor)
      }

    case _: MemberEvent ⇒ // not of interest
  }

  def nodeRing: immutable.SortedSet[Address] = {
    implicit val ringOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
      val ha = hashFor(a)
      val hb = hashFor(b)
      ha < hb || (ha == hb && Member.addressOrdering.compare(a, b) < 0)
    }
    immutable.SortedSet() ++ nodes
  }

  def hashFor(node: Address): Int = node match {
    // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
    case Address(_, _, Some(host), Some(port)) ⇒ MurmurHash.stringHash(s"${host}:${port}")
    case _                                     ⇒ 0
  }

}

