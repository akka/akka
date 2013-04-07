/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import java.net.URLEncoder
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
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

  override def postStop(): Unit = {
    super.postStop()
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
    case PingTick               ⇒ sendGetContacts()
    case Pong                   ⇒
    case RefreshContactsTick    ⇒
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
 * Extension that starts [[ClusterReceptionist]] and accompanying [[DistributedPubSubMediator]]
 * with settings defined in config section `akka.contrib.cluster.receptionist`.
 */
object ClusterReceptionistExtension extends ExtensionId[ClusterReceptionistExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): ClusterReceptionistExtension = super.get(system)

  override def lookup = ClusterReceptionistExtension

  override def createExtension(system: ExtendedActorSystem): ClusterReceptionistExtension =
    new ClusterReceptionistExtension(system)
}

class ClusterReceptionistExtension(system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config.getConfig("akka.contrib.cluster.receptionist")
  private val role: Option[String] = config.getString("role") match {
    case "" ⇒ None
    case r  ⇒ Some(r)
  }

  /**
   * Returns true if this member is not tagged with the role configured for the
   * receptionist.
   */
  def isTerminated: Boolean = Cluster(system).isTerminated || !role.forall(Cluster(system).selfRoles.contains)

  /**
   * Register the actors that should be reachable for the clients in this [[DistributedPubSubMediator]].
   */
  val pubSubMediator: ActorRef = {
    if (isTerminated)
      system.deadLetters
    else {
      val gossipInterval = Duration(config.getMilliseconds("pub-sub.gossip-interval"), MILLISECONDS)
      val removedTimeToLive = Duration(config.getMilliseconds("pub-sub.removed-time-to-live"), MILLISECONDS)
      val name = config.getString("pub-sub.name")
      system.actorOf(Props(new DistributedPubSubMediator(role, gossipInterval, removedTimeToLive)),
        name)
    }
  }

  /**
   * The [[ClusterReceptionist]] actor
   */
  val receptionist: ActorRef = {
    if (isTerminated)
      system.deadLetters
    else {
      val numberOfContacts: Int = config.getInt("number-of-contacts")
      val responseTunnelReceiveTimeout =
        Duration(config.getMilliseconds("response-tunnel-receive-timeout"), MILLISECONDS)
      val name = config.getString("name")
      system.actorOf(Props(new ClusterReceptionist(pubSubMediator, role, numberOfContacts,
        responseTunnelReceiveTimeout)), name)
    }
  }
}

/**
 * INTERNAL API
 */
private[pattern] object ClusterReceptionist {
  @SerialVersionUID(1L)
  case object GetContacts
  @SerialVersionUID(1L)
  case class Contacts(contactPoints: immutable.IndexedSeq[ActorRef])
  @SerialVersionUID(1L)
  case object Ping
  @SerialVersionUID(1L)
  case object Pong

  // FIXME change to akka.actor.Identify when that is in master
  @SerialVersionUID(1L)
  case class Identify(messageId: Any)
  @SerialVersionUID(1L)
  case class ActorIdentity(correlationId: Any, ref: Option[ActorRef])

  def roleOption(role: String): Option[String] = role match {
    case null | "" ⇒ None
    case _         ⇒ Some(role)
  }

  /**
   * Replies are tunneled via this actor, child of the receptionist, to avoid
   * inbound connections from other cluster nodes to the client.
   */
  class ClientResponseTunnel(client: ActorRef, timeout: FiniteDuration) extends Actor {
    context.setReceiveTimeout(timeout)
    def receive = {
      case Ping           ⇒ // keep alive from client
      case ReceiveTimeout ⇒ context stop self
      case msg            ⇒ client forward msg
    }
  }

}

/**
 * [[ClusterClient]] connects to this actor to retrieve. The `ClusterReceptionist` is
 * supposed to be started on all nodes, or all nodes with specified role, in the cluster.
 * The receptionist can be started with the [[ClusterReceptionistExtension]] or as an
 * ordinary actor.
 *
 * The receptionist forwards messages from the client to the associated [[DistributedPubSubMediator]],
 * i.e. the client can send messages to any actor in the cluster that is registered in the
 * `DistributedPubSubMediator`. Messages from the client are wrapped in
 * [[DistributedPubSubMediator.Send]], [[DistributedPubSubMediator.SendToAll]]
 * or [[DistributedPubSubMediator.Publish]] with the semantics described in
 * [[DistributedPubSubMediator]].
 *
 * Response messages from the destination actor is tunneled via the receptionist
 * to avoid inbound connections from other cluster nodes to the client, i.e.
 * the `sender`, as seen by the destination actor, is not the client itself.
 * The `sender` of the response messages, as seen by the client, is preserved
 * as the original sender, so the client can choose to send subsequent messages
 * directly to the actor in the cluster.
 */
class ClusterReceptionist(
  pubSubMediator: ActorRef,
  role: Option[String],
  numberOfContacts: Int = 3,
  responseTunnelReceiveTimeout: FiniteDuration = 30.seconds)
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

  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${selfAddress}] doesn't have the role [$role]")

  var nodes: immutable.SortedSet[Address] = {
    def hashFor(node: Address): Int = node match {
      // cluster node identifier is the host and port of the address; protocol and system is assumed to be the same
      case Address(_, _, Some(host), Some(port)) ⇒ MurmurHash.stringHash(s"${host}:${port}")
      case _ ⇒
        throw new IllegalStateException(s"Unexpected address without host/port: [$node]")
    }
    implicit val ringOrdering: Ordering[Address] = Ordering.fromLessThan[Address] { (a, b) ⇒
      val ha = hashFor(a)
      val hb = hashFor(b)
      ha < hb || (ha == hb && Member.addressOrdering.compare(a, b) < 0)
    }
    immutable.SortedSet()
  }
  val virtualNodesFactor = 10
  var consistentHash: ConsistentHash[Address] = ConsistentHash(nodes, virtualNodesFactor)

  override def preStart(): Unit = {
    super.preStart()
    require(!cluster.isTerminated, "Cluster node must not be terminated")
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster unsubscribe self
  }

  def matchingRole(m: Member): Boolean = role.forall(m.hasRole)

  def responseTunnel(client: ActorRef): ActorRef = {
    val encName = URLEncoder.encode(client.path.toSerializationFormat, "utf-8")
    context.child(encName) match {
      case Some(tunnel) ⇒ tunnel
      case None ⇒
        context.actorOf(Props(new ClientResponseTunnel(client, responseTunnelReceiveTimeout)), encName)
    }
  }

  def receive = {
    case msg @ (_: Send | _: SendToAll | _: Publish) ⇒
      pubSubMediator.tell(msg, responseTunnel(sender))

    case Ping ⇒
      responseTunnel(sender) ! Ping // keep alive
      sender ! Pong

    // FIXME remove when akka.actor.Identify when is in master
    case Identify(messageId) ⇒
      sender ! ActorIdentity(messageId, Some(self))

    case GetContacts ⇒
      // Consistent hashing is used to ensure that the reply to GetContacts
      // is the same from all nodes (most of the time) and it also
      // load balances the client connections among the nodes in the cluster.
      if (numberOfContacts >= nodes.size) {
        sender ! Contacts(nodes.map(a ⇒ context.actorFor(self.path.toStringWithAddress(a)))(collection.breakOut))
      } else {
        // using toStringWithAddress in case the client is local, normally it is not, and
        // toStringWithAddress will use the remote address of the client
        val a = consistentHash.nodeFor(sender.path.toStringWithAddress(cluster.selfAddress))
        val slice = {
          val first = nodes.from(a).tail.take(numberOfContacts)
          if (first.size == numberOfContacts) first
          else first ++ nodes.take(numberOfContacts - first.size)
        }
        sender ! Contacts(slice.map(a ⇒ context.actorFor(self.path.toStringWithAddress(a)))(collection.breakOut))
      }

    case state: CurrentClusterState ⇒
      nodes = nodes.empty ++ state.members.collect { case m if m.status != MemberStatus.Joining && matchingRole(m) ⇒ m.address }
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

}

