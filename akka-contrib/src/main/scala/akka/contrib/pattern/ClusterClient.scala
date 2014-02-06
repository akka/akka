/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import java.net.URLEncoder
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Identify
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.routing.ConsistentHash
import akka.routing.MurmurHash
import akka.actor.Stash
import akka.actor.Cancellable

object ClusterClient {

  /**
   * Scala API: Factory method for `ClusterClient` [[akka.actor.Props]].
   */
  def props(
    initialContacts: Set[ActorSelection],
    establishingGetContactsInterval: FiniteDuration = 3.second,
    refreshContactsInterval: FiniteDuration = 1.minute): Props =
    Props(classOf[ClusterClient], initialContacts, establishingGetContactsInterval, refreshContactsInterval).
      withMailbox("akka.contrib.cluster.client.mailbox")

  /**
   * Java API: Factory method for `ClusterClient` [[akka.actor.Props]].
   */
  def props(
    initialContacts: java.util.Set[ActorSelection],
    establishingGetContactsInterval: FiniteDuration,
    refreshContactsInterval: FiniteDuration): Props = {
    import scala.collection.JavaConverters._
    props(initialContacts.asScala.toSet, establishingGetContactsInterval, refreshContactsInterval)
  }

  /**
   * Java API: Factory method for `ClusterClient` [[akka.actor.Props]] with
   * default values.
   */
  def defaultProps(initialContacts: java.util.Set[ActorSelection]): Props = {
    import scala.collection.JavaConverters._
    props(initialContacts.asScala.toSet)
  }

  @SerialVersionUID(1L)
  case class Send(path: String, msg: Any, localAffinity: Boolean) {
    /**
     * Convenience constructor with `localAffinity` false
     */
    def this(path: String, msg: Any) = this(path, msg, localAffinity = false)
  }
  @SerialVersionUID(1L)
  case class SendToAll(path: String, msg: Any)
  @SerialVersionUID(1L)
  case class Publish(topic: String, msg: Any)

  /**
   * INTERNAL API
   */
  private[pattern] object Internal {
    case object RefreshContactsTick
  }
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
 * that is registered in the [[ClusterReceptionist]].
 * Messages are wrapped in [[ClusterClient.Send]], [[ClusterClient.SendToAll]]
 * or [[ClusterClient.Publish]].
 *
 * 1. [[ClusterClient.Send]] -
 * The message will be delivered to one recipient with a matching path, if any such
 * exists. If several entries match the path the message will be delivered
 * to one random destination. The sender of the message can specify that local
 * affinity is preferred, i.e. the message is sent to an actor in the same local actor
 * system as the used receptionist actor, if any such exists, otherwise random to any other
 * matching entry.
 *
 * 2. [[ClusterClient.SendToAll]] -
 * The message will be delivered to all recipients with a matching path.
 *
 * 3. [[ClusterClient.Publish]] -
 * The message will be delivered to all recipients Actors that have been registered as subscribers to
 * to the named topic.
 *
 *  Use the factory method [[ClusterClient#props]]) to create the
 * [[akka.actor.Props]] for the actor.
 */
class ClusterClient(
  initialContacts: Set[ActorSelection],
  establishingGetContactsInterval: FiniteDuration,
  refreshContactsInterval: FiniteDuration)
  extends Actor with Stash with ActorLogging {

  import ClusterClient._
  import ClusterClient.Internal._
  import ClusterReceptionist.Internal._

  var contacts: immutable.IndexedSeq[ActorSelection] = initialContacts.toVector
  sendGetContacts()

  import context.dispatcher
  var refreshContactsTask: Option[Cancellable] = None
  scheduleRefreshContactsTick(establishingGetContactsInterval)
  self ! RefreshContactsTick

  def scheduleRefreshContactsTick(interval: FiniteDuration): Unit = {
    refreshContactsTask foreach { _.cancel() }
    refreshContactsTask = Some(context.system.scheduler.schedule(
      interval, interval, self, RefreshContactsTick))
  }

  override def postStop(): Unit = {
    super.postStop()
    refreshContactsTask foreach { _.cancel() }
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
      context.watch(receptionist)
      scheduleRefreshContactsTick(refreshContactsInterval)
      unstashAll()
      context.become(active(receptionist))
    case ActorIdentity(_, None) ⇒ // ok, use another instead
    case RefreshContactsTick    ⇒ sendGetContacts()
    case msg                    ⇒ stash()
  }

  def active(receptionist: ActorRef): Actor.Receive = {
    case Send(path, msg, localAffinity) ⇒
      receptionist forward DistributedPubSubMediator.Send(path, msg, localAffinity)
    case SendToAll(path, msg) ⇒
      receptionist forward DistributedPubSubMediator.SendToAll(path, msg)
    case Publish(topic, msg) ⇒
      receptionist forward DistributedPubSubMediator.Publish(topic, msg)
    case RefreshContactsTick ⇒
      receptionist ! GetContacts
    case Contacts(contactPoints) ⇒
      // refresh of contacts
      if (contactPoints.nonEmpty)
        contacts = contactPoints
    case Terminated(`receptionist`) ⇒
      log.info("Lost contact with [{}], restablishing connection", receptionist)
      sendGetContacts()
      scheduleRefreshContactsTick(establishingGetContactsInterval)
      context.become(establishing)
    case _: ActorIdentity ⇒ // ok, from previous establish, already handled
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
 * The [[DistributedPubSubMediator]] is started by the [[DistributedPubSubExtension]].
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
  private def pubSubMediator: ActorRef = DistributedPubSubExtension(system).mediator

  /**
   * Register an actor that should be reachable for the clients.
   * The clients can send messages to this actor with `Send` or `SendToAll` using
   * the path elements of the `ActorRef`, e.g. `"/user/myservice"`.
   */
  def registerService(actor: ActorRef): Unit =
    pubSubMediator ! DistributedPubSubMediator.Put(actor)

  /**
   * A registered actor will be automatically unregistered when terminated,
   * but it can also be explicitly unregistered before termination.
   */
  def unregisterService(actor: ActorRef): Unit =
    pubSubMediator ! DistributedPubSubMediator.Remove(actor.path.toStringWithoutAddress)

  /**
   * Register an actor that should be reachable for the clients to a named topic.
   * Several actors can be registered to the same topic name, and all will receive
   * published messages.
   * The client can publish messages to this topic with `Publish`.
   */
  def registerSubscriber(topic: String, actor: ActorRef): Unit =
    pubSubMediator ! DistributedPubSubMediator.Subscribe(topic, actor)

  /**
   * A registered subscriber will be automatically unregistered when terminated,
   * but it can also be explicitly unregistered before termination.
   */
  def unregisterSubscriber(topic: String, actor: ActorRef): Unit =
    pubSubMediator ! DistributedPubSubMediator.Unsubscribe(topic, actor)

  /**
   * The [[ClusterReceptionist]] actor
   */
  private val receptionist: ActorRef = {
    if (isTerminated)
      system.deadLetters
    else {
      val numberOfContacts: Int = config.getInt("number-of-contacts")
      val responseTunnelReceiveTimeout =
        config.getDuration("response-tunnel-receive-timeout", MILLISECONDS).millis
      val name = config.getString("name")
      // important to use val mediator here to activate it outside of ClusterReceptionist constructor
      val mediator = pubSubMediator
      system.actorOf(ClusterReceptionist.props(mediator, role, numberOfContacts,
        responseTunnelReceiveTimeout), name)
    }
  }
}

object ClusterReceptionist {

  /**
   * Scala API: Factory method for `ClusterReceptionist` [[akka.actor.Props]].
   */
  def props(
    pubSubMediator: ActorRef,
    role: Option[String],
    numberOfContacts: Int = 3,
    responseTunnelReceiveTimeout: FiniteDuration = 30.seconds): Props =
    Props(classOf[ClusterReceptionist], pubSubMediator, role, numberOfContacts, responseTunnelReceiveTimeout)

  /**
   * Java API: Factory method for `ClusterReceptionist` [[akka.actor.Props]].
   */
  def props(
    pubSubMediator: ActorRef,
    role: String,
    numberOfContacts: Int,
    responseTunnelReceiveTimeout: FiniteDuration): Props =
    props(pubSubMediator, Internal.roleOption(role), numberOfContacts, responseTunnelReceiveTimeout)

  /**
   * Java API: Factory method for `ClusterReceptionist` [[akka.actor.Props]]
   * with default values.
   */
  def props(
    pubSubMediator: ActorRef,
    role: String): Props =
    props(pubSubMediator, Internal.roleOption(role))

  /**
   * INTERNAL API
   */
  private[pattern] object Internal {
    @SerialVersionUID(1L)
    case object GetContacts
    @SerialVersionUID(1L)
    case class Contacts(contactPoints: immutable.IndexedSeq[ActorSelection])
    @SerialVersionUID(1L)
    case object Ping

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
      context.watch(client)
      def receive = {
        case Ping                 ⇒ // keep alive from client
        case ReceiveTimeout       ⇒ context stop self
        case Terminated(`client`) ⇒ context stop self
        case msg                  ⇒ client forward msg
      }
    }
  }

}

/**
 * [[ClusterClient]] connects to this actor to retrieve. The `ClusterReceptionist` is
 * supposed to be started on all nodes, or all nodes with specified role, in the cluster.
 * The receptionist can be started with the [[ClusterReceptionistExtension]] or as an
 * ordinary actor (use the factory method [[ClusterReceptionist#props]]).
 *
 * The receptionist forwards messages from the client to the associated [[DistributedPubSubMediator]],
 * i.e. the client can send messages to any actor in the cluster that is registered in the
 * `DistributedPubSubMediator`. Messages from the client are wrapped in
 * [[DistributedPubSubMediator.Send]], [[DistributedPubSubMediator.SendToAll]]
 * or [[DistributedPubSubMediator.Publish]] with the semantics described in
 * [[DistributedPubSubMediator]].
 *
 * Response messages from the destination actor are tunneled via the receptionist
 * to avoid inbound connections from other cluster nodes to the client, i.e.
 * the `sender`, as seen by the destination actor, is not the client itself.
 * The `sender` of the response messages, as seen by the client, is preserved
 * as the original sender, so the client can choose to send subsequent messages
 * directly to the actor in the cluster.
 */
class ClusterReceptionist(
  pubSubMediator: ActorRef,
  role: Option[String],
  numberOfContacts: Int,
  responseTunnelReceiveTimeout: FiniteDuration)
  extends Actor with ActorLogging {

  import DistributedPubSubMediator.{ Send, SendToAll, Publish }

  import ClusterReceptionist.Internal._

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
        context.actorOf(Props(classOf[ClientResponseTunnel], client, responseTunnelReceiveTimeout), encName)
    }
  }

  def receive = {
    case msg @ (_: Send | _: SendToAll | _: Publish) ⇒
      val tunnel = responseTunnel(sender())
      tunnel ! Ping // keep alive
      pubSubMediator.tell(msg, tunnel)

    case GetContacts ⇒
      // Consistent hashing is used to ensure that the reply to GetContacts
      // is the same from all nodes (most of the time) and it also
      // load balances the client connections among the nodes in the cluster.
      if (numberOfContacts >= nodes.size) {
        sender() ! Contacts(nodes.map(a ⇒ context.actorSelection(self.path.toStringWithAddress(a)))(collection.breakOut))
      } else {
        // using toStringWithAddress in case the client is local, normally it is not, and
        // toStringWithAddress will use the remote address of the client
        val a = consistentHash.nodeFor(sender().path.toStringWithAddress(cluster.selfAddress))
        val slice = {
          val first = nodes.from(a).tail.take(numberOfContacts)
          if (first.size == numberOfContacts) first
          else first ++ nodes.take(numberOfContacts - first.size)
        }
        sender() ! Contacts(slice.map(a ⇒ context.actorSelection(self.path.toStringWithAddress(a)))(collection.breakOut))
      }

    case state: CurrentClusterState ⇒
      nodes = nodes.empty ++ state.members.collect { case m if m.status != MemberStatus.Joining && matchingRole(m) ⇒ m.address }
      consistentHash = ConsistentHash(nodes, virtualNodesFactor)

    case MemberUp(m) ⇒
      if (matchingRole(m)) {
        nodes += m.address
        consistentHash = ConsistentHash(nodes, virtualNodesFactor)
      }

    case MemberRemoved(m, _) ⇒
      if (m.address == selfAddress)
        context stop self
      else if (matchingRole(m)) {
        nodes -= m.address
        consistentHash = ConsistentHash(nodes, virtualNodesFactor)
      }

    case _: MemberEvent ⇒ // not of interest
  }

}

