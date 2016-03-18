/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.client

import java.net.URLEncoder
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Cancellable
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Identify
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.pubsub._
import akka.japi.Util.immutableSeq
import akka.routing.ConsistentHash
import akka.routing.MurmurHash
import com.typesafe.config.Config
import akka.actor.DeadLetterSuppression
import akka.remote.DeadlineFailureDetector
import akka.dispatch.Dispatchers

object ClusterClientSettings {
  /**
   * Create settings from the default configuration
   * `akka.cluster.client`.
   */
  def apply(system: ActorSystem): ClusterClientSettings =
    apply(system.settings.config.getConfig("akka.cluster.client"))

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.client`.
   */
  def apply(config: Config): ClusterClientSettings = {
    val initialContacts = immutableSeq(config.getStringList("initial-contacts")).map(ActorPath.fromString).toSet
    new ClusterClientSettings(
      initialContacts,
      establishingGetContactsInterval = config.getDuration("establishing-get-contacts-interval", MILLISECONDS).millis,
      refreshContactsInterval = config.getDuration("refresh-contacts-interval", MILLISECONDS).millis,
      heartbeatInterval = config.getDuration("heartbeat-interval", MILLISECONDS).millis,
      acceptableHeartbeatPause = config.getDuration("acceptable-heartbeat-pause", MILLISECONDS).millis,
      bufferSize = config.getInt("buffer-size"),
      reconnectTimeout = config.getString("reconnect-timeout") match {
        case "off" ⇒ None
        case _     ⇒ Some(config.getDuration("reconnect-timeout", MILLISECONDS).millis)
      })
  }

  /**
   * Java API: Create settings from the default configuration
   * `akka.cluster.client`.
   */
  def create(system: ActorSystem): ClusterClientSettings = apply(system)

  /**
   * Java API: Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.client`.
   */
  def create(config: Config): ClusterClientSettings = apply(config)

}

/**
 * @param initialContacts Actor paths of the `ClusterReceptionist` actors on
 *   the servers (cluster nodes) that the client will try to contact initially.
 *   It is mandatory to specify at least one initial contact. The path of the
 *   default receptionist is
 *   "akka.tcp://system@hostname:port/system/receptionist"
 * @param establishingGetContactsInterval Interval at which the client retries
 *   to establish contact with one of ClusterReceptionist on the servers (cluster nodes)
 * @param refreshContactsInterval Interval at which the client will ask the
 *   `ClusterReceptionist` for new contact points to be used for next reconnect.
 * @param heartbeatInterval How often failure detection heartbeat messages for detection
 *   of failed connections should be sent.
 * @param acceptableHeartbeatPause Number of potentially lost/delayed heartbeats that will
 *   be accepted before considering it to be an anomaly. The ClusterClient is using the
 *   [[akka.remote.DeadlineFailureDetector]], which will trigger if there are no heartbeats
 *   within the duration `heartbeatInterval + acceptableHeartbeatPause`.
 * @param bufferSize If connection to the receptionist is not established the client
 *   will buffer this number of messages and deliver them the connection is established.
 *   When the buffer is full old messages will be dropped when new messages are sent via the
 *   client. Use 0 to disable buffering, i.e. messages will be dropped immediately if the
 *   location of the receptionist is unavailable.
 * @param reconnectTimeout If the connection to the receptionist is lost and cannot
 *   be re-established within this duration the cluster client will be stopped. This makes it possible
 *   to watch it from another actor and possibly acquire a new list of initialContacts from some
 *   external service registry
 */
final class ClusterClientSettings(
  val initialContacts: Set[ActorPath],
  val establishingGetContactsInterval: FiniteDuration,
  val refreshContactsInterval: FiniteDuration,
  val heartbeatInterval: FiniteDuration,
  val acceptableHeartbeatPause: FiniteDuration,
  val bufferSize: Int,
  val reconnectTimeout: Option[FiniteDuration]) extends NoSerializationVerificationNeeded {

  require(bufferSize >= 0 && bufferSize <= 10000, "bufferSize must be >= 0 and <= 10000")

  /**
   * For binary/source compatibility
   */
  def this(
    initialContacts: Set[ActorPath],
    establishingGetContactsInterval: FiniteDuration,
    refreshContactsInterval: FiniteDuration,
    heartbeatInterval: FiniteDuration,
    acceptableHeartbeatPause: FiniteDuration,
    bufferSize: Int) =
    this(initialContacts, establishingGetContactsInterval, refreshContactsInterval, heartbeatInterval,
      acceptableHeartbeatPause, bufferSize, None)

  /**
   * Scala API
   */
  def withInitialContacts(initialContacts: Set[ActorPath]): ClusterClientSettings = {
    require(initialContacts.nonEmpty, "initialContacts must be defined")
    copy(initialContacts = initialContacts)
  }

  /**
   * Java API
   */
  def withInitialContacts(initialContacts: java.util.Set[ActorPath]): ClusterClientSettings = {
    import scala.collection.JavaConverters._
    withInitialContacts(initialContacts.asScala.toSet)
  }

  def withEstablishingGetContactsInterval(establishingGetContactsInterval: FiniteDuration): ClusterClientSettings =
    copy(establishingGetContactsInterval = establishingGetContactsInterval)

  def withRefreshContactsInterval(refreshContactsInterval: FiniteDuration): ClusterClientSettings =
    copy(refreshContactsInterval = refreshContactsInterval)

  def withHeartbeat(heartbeatInterval: FiniteDuration, acceptableHeartbeatPause: FiniteDuration): ClusterClientSettings =
    copy(heartbeatInterval = heartbeatInterval, acceptableHeartbeatPause = acceptableHeartbeatPause)

  def withBufferSize(bufferSize: Int): ClusterClientSettings =
    copy(bufferSize = bufferSize)

  def withReconnectTimeout(reconnectTimeout: Option[FiniteDuration]): ClusterClientSettings =
    copy(reconnectTimeout = reconnectTimeout)

  private def copy(
    initialContacts: Set[ActorPath] = initialContacts,
    establishingGetContactsInterval: FiniteDuration = establishingGetContactsInterval,
    refreshContactsInterval: FiniteDuration = refreshContactsInterval,
    heartbeatInterval: FiniteDuration = heartbeatInterval,
    acceptableHeartbeatPause: FiniteDuration = acceptableHeartbeatPause,
    bufferSize: Int = bufferSize,
    reconnectTimeout: Option[FiniteDuration] = reconnectTimeout): ClusterClientSettings =
    new ClusterClientSettings(initialContacts, establishingGetContactsInterval, refreshContactsInterval,
      heartbeatInterval, acceptableHeartbeatPause, bufferSize, reconnectTimeout)
}

object ClusterClient {

  /**
   * Scala API: Factory method for `ClusterClient` [[akka.actor.Props]].
   */
  def props(settings: ClusterClientSettings): Props =
    Props(new ClusterClient(settings)).withDeploy(Deploy.local)

  @SerialVersionUID(1L)
  final case class Send(path: String, msg: Any, localAffinity: Boolean) {
    /**
     * Convenience constructor with `localAffinity` false
     */
    def this(path: String, msg: Any) = this(path, msg, localAffinity = false)
  }
  @SerialVersionUID(1L)
  final case class SendToAll(path: String, msg: Any)
  @SerialVersionUID(1L)
  final case class Publish(topic: String, msg: Any)

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    case object RefreshContactsTick
    case object HeartbeatTick
    case object ReconnectTimeout
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
 *
 * If the receptionist is not currently available, the client will buffer the messages
 * and then deliver them when the connection to the receptionist has been established.
 * The size of the buffer is configurable and it can be disabled by using a buffer size
 * of 0. When the buffer is full old messages will be dropped when new messages are sent
 * via the client.
 *
 * Note that this is a best effort implementation: messages can always be lost due to the distributed
 * nature of the actors involved.
 */
final class ClusterClient(settings: ClusterClientSettings) extends Actor with ActorLogging {

  import ClusterClient._
  import ClusterClient.Internal._
  import ClusterReceptionist.Internal._
  import settings._

  require(initialContacts.nonEmpty, "initialContacts must be defined")

  val failureDetector = new DeadlineFailureDetector(acceptableHeartbeatPause, heartbeatInterval)

  val initialContactsSel: immutable.IndexedSeq[ActorSelection] =
    initialContacts.map(context.actorSelection).toVector
  var contacts = initialContactsSel
  sendGetContacts()

  import context.dispatcher
  val heartbeatTask = context.system.scheduler.schedule(
    heartbeatInterval, heartbeatInterval, self, HeartbeatTick)
  var refreshContactsTask: Option[Cancellable] = None
  scheduleRefreshContactsTick(establishingGetContactsInterval)
  self ! RefreshContactsTick

  val buffer = new java.util.LinkedList[(Any, ActorRef)]

  def scheduleRefreshContactsTick(interval: FiniteDuration): Unit = {
    refreshContactsTask foreach { _.cancel() }
    refreshContactsTask = Some(context.system.scheduler.schedule(
      interval, interval, self, RefreshContactsTick))
  }

  override def postStop(): Unit = {
    super.postStop()
    heartbeatTask.cancel()
    refreshContactsTask foreach { _.cancel() }
  }

  def receive = establishing

  def establishing: Actor.Receive = {
    val connectTimerCancelable = settings.reconnectTimeout.map { timeout ⇒
      context.system.scheduler.scheduleOnce(timeout, self, ReconnectTimeout)
    }

    {
      case Contacts(contactPoints) ⇒
        if (contactPoints.nonEmpty) {
          contacts = contactPoints.map(context.actorSelection)
          contacts foreach { _ ! Identify(None) }
        }
      case ActorIdentity(_, Some(receptionist)) ⇒
        log.info("Connected to [{}]", receptionist.path)
        scheduleRefreshContactsTick(refreshContactsInterval)
        sendBuffered(receptionist)
        context.become(active(receptionist))
        connectTimerCancelable.foreach(_.cancel())
        failureDetector.heartbeat()
      case ActorIdentity(_, None) ⇒ // ok, use another instead
      case HeartbeatTick ⇒
        failureDetector.heartbeat()
      case RefreshContactsTick ⇒ sendGetContacts()
      case Send(path, msg, localAffinity) ⇒
        buffer(DistributedPubSubMediator.Send(path, msg, localAffinity))
      case SendToAll(path, msg) ⇒
        buffer(DistributedPubSubMediator.SendToAll(path, msg))
      case Publish(topic, msg) ⇒
        buffer(DistributedPubSubMediator.Publish(topic, msg))
      case ReconnectTimeout ⇒
        log.warning("Receptionist reconnect not successful within {} stopping cluster client", settings.reconnectTimeout)
        context.stop(self)
    }
  }

  def active(receptionist: ActorRef): Actor.Receive = {
    case Send(path, msg, localAffinity) ⇒
      receptionist forward DistributedPubSubMediator.Send(path, msg, localAffinity)
    case SendToAll(path, msg) ⇒
      receptionist forward DistributedPubSubMediator.SendToAll(path, msg)
    case Publish(topic, msg) ⇒
      receptionist forward DistributedPubSubMediator.Publish(topic, msg)
    case HeartbeatTick ⇒
      if (!failureDetector.isAvailable) {
        log.info("Lost contact with [{}], restablishing connection", receptionist)
        sendGetContacts()
        scheduleRefreshContactsTick(establishingGetContactsInterval)
        context.become(establishing)
        failureDetector.heartbeat()
      } else
        receptionist ! Heartbeat
    case HeartbeatRsp ⇒
      failureDetector.heartbeat()
    case RefreshContactsTick ⇒
      receptionist ! GetContacts
    case Contacts(contactPoints) ⇒
      // refresh of contacts
      if (contactPoints.nonEmpty)
        contacts = contactPoints.map(context.actorSelection)
    case _: ActorIdentity ⇒ // ok, from previous establish, already handled
  }

  def sendGetContacts(): Unit = {
    val sendTo =
      if (contacts.isEmpty) initialContactsSel
      else if (contacts.size == 1) (initialContactsSel union contacts)
      else contacts
    if (log.isDebugEnabled)
      log.debug(s"""Sending GetContacts to [${sendTo.mkString(",")}]""")
    sendTo.foreach { _ ! GetContacts }
  }

  def buffer(msg: Any): Unit =
    if (settings.bufferSize == 0)
      log.debug("Receptionist not available and buffering is disabled, dropping message [{}]", msg.getClass.getName)
    else if (buffer.size == settings.bufferSize) {
      val (m, _) = buffer.removeFirst()
      log.debug("Receptionist not available, buffer is full, dropping first message [{}]", m.getClass.getName)
      buffer.addLast((msg, sender()))
    } else {
      log.debug("Receptionist not available, buffering message type [{}]", msg.getClass.getName)
      buffer.addLast((msg, sender()))
    }

  def sendBuffered(receptionist: ActorRef): Unit = {
    log.debug("Sending buffered messages to receptionist")
    while (!buffer.isEmpty) {
      val (msg, snd) = buffer.removeFirst()
      receptionist.tell(msg, snd)
    }
  }
}

object ClusterClientReceptionist extends ExtensionId[ClusterClientReceptionist] with ExtensionIdProvider {
  override def get(system: ActorSystem): ClusterClientReceptionist = super.get(system)

  override def lookup = ClusterClientReceptionist

  override def createExtension(system: ExtendedActorSystem): ClusterClientReceptionist =
    new ClusterClientReceptionist(system)
}

/**
 * Extension that starts [[ClusterReceptionist]] and accompanying [[akka.cluster.pubsub.DistributedPubSubMediator]]
 * with settings defined in config section `akka.cluster.client.receptionist`.
 * The [[akka.cluster.pubsub.DistributedPubSubMediator]] is started by the [[akka.cluster.pubsub.DistributedPubSub]] extension.
 */
final class ClusterClientReceptionist(system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config.getConfig("akka.cluster.client.receptionist")
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
  private def pubSubMediator: ActorRef = DistributedPubSub(system).mediator

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
      val name = config.getString("name")
      val dispatcher = config.getString("use-dispatcher") match {
        case "" ⇒ Dispatchers.DefaultDispatcherId
        case id ⇒ id
      }
      // important to use val mediator here to activate it outside of ClusterReceptionist constructor
      val mediator = pubSubMediator
      system.systemActorOf(ClusterReceptionist.props(mediator, ClusterReceptionistSettings(config))
        .withDispatcher(dispatcher), name)
    }
  }
}

object ClusterReceptionistSettings {
  /**
   * Create settings from the default configuration
   * `akka.cluster.client.receptionist`.
   */
  def apply(system: ActorSystem): ClusterReceptionistSettings =
    apply(system.settings.config.getConfig("akka.cluster.client.receptionist"))

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.client.receptionist`.
   */
  def apply(config: Config): ClusterReceptionistSettings =
    new ClusterReceptionistSettings(
      role = roleOption(config.getString("role")),
      numberOfContacts = config.getInt("number-of-contacts"),
      responseTunnelReceiveTimeout = config.getDuration("response-tunnel-receive-timeout", MILLISECONDS).millis)

  /**
   * Java API: Create settings from the default configuration
   * `akka.cluster.client.receptionist`.
   */
  def create(system: ActorSystem): ClusterReceptionistSettings = apply(system)

  /**
   * Java API: Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.client.receptionist`.
   */
  def create(config: Config): ClusterReceptionistSettings = apply(config)

  /**
   * INTERNAL API
   */
  private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)

}

/**
 * @param role Start the receptionist on members tagged with this role.
 *   All members are used if undefined.
 * @param numberOfContacts The receptionist will send this number of contact points to the client
 * @param responseTunnelReceiveTimeout The actor that tunnel response messages to the
 *   client will be stopped after this time of inactivity.
 */
final class ClusterReceptionistSettings(
  val role: Option[String],
  val numberOfContacts: Int,
  val responseTunnelReceiveTimeout: FiniteDuration) extends NoSerializationVerificationNeeded {

  def withRole(role: String): ClusterReceptionistSettings = copy(role = ClusterReceptionistSettings.roleOption(role))

  def withRole(role: Option[String]): ClusterReceptionistSettings = copy(role = role)

  def withNumberOfContacts(numberOfContacts: Int): ClusterReceptionistSettings =
    copy(numberOfContacts = numberOfContacts)

  def withResponseTunnelReceiveTimeout(responseTunnelReceiveTimeout: FiniteDuration): ClusterReceptionistSettings =
    copy(responseTunnelReceiveTimeout = responseTunnelReceiveTimeout)

  private def copy(
    role: Option[String] = role,
    numberOfContacts: Int = numberOfContacts,
    responseTunnelReceiveTimeout: FiniteDuration = responseTunnelReceiveTimeout): ClusterReceptionistSettings =
    new ClusterReceptionistSettings(role, numberOfContacts, responseTunnelReceiveTimeout)

}

/**
 * Marker trait for remote messages with special serializer.
 */
sealed trait ClusterClientMessage extends Serializable

object ClusterReceptionist {

  /**
   * Scala API: Factory method for `ClusterReceptionist` [[akka.actor.Props]].
   */
  def props(
    pubSubMediator: ActorRef,
    settings: ClusterReceptionistSettings): Props =
    Props(new ClusterReceptionist(pubSubMediator, settings)).withDeploy(Deploy.local)

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    @SerialVersionUID(1L)
    case object GetContacts extends ClusterClientMessage with DeadLetterSuppression
    @SerialVersionUID(1L)
    final case class Contacts(contactPoints: immutable.IndexedSeq[String]) extends ClusterClientMessage
    @SerialVersionUID(1L)
    case object Heartbeat extends ClusterClientMessage with DeadLetterSuppression
    @SerialVersionUID(1L)
    case object HeartbeatRsp extends ClusterClientMessage with DeadLetterSuppression
    @SerialVersionUID(1L)
    case object Ping extends DeadLetterSuppression

    /**
     * Replies are tunneled via this actor, child of the receptionist, to avoid
     * inbound connections from other cluster nodes to the client.
     */
    class ClientResponseTunnel(client: ActorRef, timeout: FiniteDuration) extends Actor with ActorLogging {
      context.setReceiveTimeout(timeout)
      def receive = {
        case Ping ⇒ // keep alive from client
        case ReceiveTimeout ⇒
          log.debug("ClientResponseTunnel for client [{}] stopped due to inactivity", client.path)
          context stop self
        case msg ⇒ client.tell(msg, Actor.noSender)
      }
    }
  }

}

/**
 * [[ClusterClient]] connects to this actor to retrieve. The `ClusterReceptionist` is
 * supposed to be started on all nodes, or all nodes with specified role, in the cluster.
 * The receptionist can be started with the [[ClusterClientReceptionist]] or as an
 * ordinary actor (use the factory method [[ClusterReceptionist#props]]).
 *
 * The receptionist forwards messages from the client to the associated [[akka.cluster.pubsub.DistributedPubSubMediator]],
 * i.e. the client can send messages to any actor in the cluster that is registered in the
 * `DistributedPubSubMediator`. Messages from the client are wrapped in
 * [[akka.cluster.pubsub.DistributedPubSubMediator.Send]], [[akka.cluster.pubsub.DistributedPubSubMediator.SendToAll]]
 * or [[akka.cluster.pubsub.DistributedPubSubMediator.Publish]] with the semantics described in
 * [[akka.cluster.pubsub.DistributedPubSubMediator]].
 *
 * Response messages from the destination actor are tunneled via the receptionist
 * to avoid inbound connections from other cluster nodes to the client, i.e.
 * the `sender()`, as seen by the destination actor, is not the client itself.
 * The `sender()` of the response messages, as seen by the client, is `deadLetters`
 * since the client should normally send subsequent messages via the `ClusterClient`.
 * It is possible to pass the original sender inside the reply messages if
 * the client is supposed to communicate directly to the actor in the cluster.
 *
 */
final class ClusterReceptionist(pubSubMediator: ActorRef, settings: ClusterReceptionistSettings)
  extends Actor with ActorLogging {

  import DistributedPubSubMediator.{ Send, SendToAll, Publish }

  import ClusterReceptionist.Internal._
  import settings._

  val cluster = Cluster(context.system)
  val verboseHeartbeat = cluster.settings.Debug.VerboseHeartbeatLogging
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

    case Heartbeat ⇒
      if (verboseHeartbeat) log.debug("Heartbeat from client [{}]", sender().path)
      sender() ! HeartbeatRsp

    case GetContacts ⇒
      // Consistent hashing is used to ensure that the reply to GetContacts
      // is the same from all nodes (most of the time) and it also
      // load balances the client connections among the nodes in the cluster.
      if (numberOfContacts >= nodes.size) {
        val contacts = Contacts(nodes.map(a ⇒ self.path.toStringWithAddress(a))(collection.breakOut))
        if (log.isDebugEnabled)
          log.debug("Client [{}] gets contactPoints [{}] (all nodes)", sender().path, contacts.contactPoints.mkString(","))
        sender() ! contacts
      } else {
        // using toStringWithAddress in case the client is local, normally it is not, and
        // toStringWithAddress will use the remote address of the client
        val a = consistentHash.nodeFor(sender().path.toStringWithAddress(cluster.selfAddress))
        val slice = {
          val first = nodes.from(a).tail.take(numberOfContacts)
          if (first.size == numberOfContacts) first
          else first union nodes.take(numberOfContacts - first.size)
        }
        val contacts = Contacts(slice.map(a ⇒ self.path.toStringWithAddress(a))(collection.breakOut))
        if (log.isDebugEnabled)
          log.debug("Client [{}] gets contactPoints [{}]", sender().path, contacts.contactPoints.mkString(","))
        sender() ! contacts
      }

    case state: CurrentClusterState ⇒
      nodes = nodes.empty union state.members.collect { case m if m.status != MemberStatus.Joining && matchingRole(m) ⇒ m.address }
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

