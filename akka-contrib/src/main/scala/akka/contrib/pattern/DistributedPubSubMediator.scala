/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import java.net.URLEncoder
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.routing.RandomRoutingLogic
import akka.routing.RoutingLogic
import akka.routing.Routee
import akka.routing.ActorRefRoutee
import akka.routing.Router
import akka.routing.RoundRobinRoutingLogic
import akka.routing.ConsistentHashingRoutingLogic
import akka.routing.BroadcastRoutingLogic

object DistributedPubSubMediator {

  /**
   * Scala API: Factory method for `DistributedPubSubMediator` [[akka.actor.Props]].
   */
  def props(
    role: Option[String],
    routingLogic: RoutingLogic = RandomRoutingLogic(),
    gossipInterval: FiniteDuration = 1.second,
    removedTimeToLive: FiniteDuration = 2.minutes,
    maxDeltaElements: Int = 3000): Props =
    Props(classOf[DistributedPubSubMediator], role, routingLogic, gossipInterval, removedTimeToLive, maxDeltaElements)

  /**
   * Java API: Factory method for `DistributedPubSubMediator` [[akka.actor.Props]].
   */
  def props(
    role: String,
    routingLogic: RoutingLogic,
    gossipInterval: FiniteDuration,
    removedTimeToLive: FiniteDuration,
    maxDeltaElements: Int): Props =
    props(Internal.roleOption(role), routingLogic, gossipInterval, removedTimeToLive, maxDeltaElements)

  /**
   * Java API: Factory method for `DistributedPubSubMediator` [[akka.actor.Props]]
   * with default values.
   */
  def defaultProps(role: String): Props = props(Internal.roleOption(role))

  @SerialVersionUID(1L) case class Put(ref: ActorRef)
  @SerialVersionUID(1L) case class Remove(path: String)
  @SerialVersionUID(1L) case class Subscribe(topic: String, ref: ActorRef)
  @SerialVersionUID(1L) case class Unsubscribe(topic: String, ref: ActorRef)
  @SerialVersionUID(1L) case class SubscribeAck(subscribe: Subscribe)
  @SerialVersionUID(1L) case class UnsubscribeAck(unsubscribe: Unsubscribe)
  @SerialVersionUID(1L) case class Publish(topic: String, msg: Any) extends DistributedPubSubMessage
  @SerialVersionUID(1L) case class Send(path: String, msg: Any, localAffinity: Boolean) extends DistributedPubSubMessage {
    /**
     * Convenience constructor with `localAffinity` false
     */
    def this(path: String, msg: Any) = this(path, msg, localAffinity = false)
  }
  @SerialVersionUID(1L) case class SendToAll(path: String, msg: Any, allButSelf: Boolean = false) extends DistributedPubSubMessage {
    def this(path: String, msg: Any) = this(path, msg, allButSelf = false)
  }

  // Only for testing purposes, to poll/await replication
  case object Count

  /**
   * INTERNAL API
   */
  private[pattern] object Internal {
    case object Prune

    @SerialVersionUID(1L)
    case class Bucket(
      owner: Address,
      version: Long,
      content: Map[String, ValueHolder])

    @SerialVersionUID(1L)
    case class ValueHolder(version: Long, ref: Option[ActorRef]) {
      @transient lazy val routee: Option[Routee] = ref map ActorRefRoutee
    }

    @SerialVersionUID(1L)
    case class Status(versions: Map[Address, Long]) extends DistributedPubSubMessage
    @SerialVersionUID(1L)
    case class Delta(buckets: immutable.Iterable[Bucket]) extends DistributedPubSubMessage

    case object GossipTick

    def roleOption(role: String): Option[String] = role match {
      case null | "" ⇒ None
      case _         ⇒ Some(role)
    }

    class Topic(emptyTimeToLive: FiniteDuration) extends Actor {
      import context.dispatcher
      val pruneInterval: FiniteDuration = emptyTimeToLive / 2
      val pruneTask = context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune)
      var pruneDeadline: Option[Deadline] = None

      var subscribers = Set.empty[ActorRef]

      override def postStop(): Unit = {
        super.postStop()
        pruneTask.cancel()
      }

      def receive = {
        case msg @ Subscribe(_, ref) ⇒
          context watch ref
          subscribers += ref
          pruneDeadline = None
          sender().tell(SubscribeAck(msg), context.parent)
        case msg @ Unsubscribe(_, ref) ⇒
          context unwatch ref
          remove(ref)
          sender().tell(UnsubscribeAck(msg), context.parent)
        case Terminated(ref) ⇒
          remove(ref)
        case Prune ⇒
          for (d ← pruneDeadline if d.isOverdue) context stop self
        case msg ⇒
          subscribers foreach { _ forward msg }
      }

      def remove(ref: ActorRef): Unit =
        if (subscribers.contains(ref)) {
          subscribers -= ref
          if (subscribers.isEmpty)
            pruneDeadline = Some(Deadline.now + emptyTimeToLive)
        }

    }
  }
}

/**
 * Marker trait for remote messages with special serializer.
 */
trait DistributedPubSubMessage extends Serializable

/**
 * This actor manages a registry of actor references and replicates
 * the entries to peer actors among all cluster nodes or a group of nodes
 * tagged with a specific role.
 *
 * The `DistributedPubSubMediator` is supposed to be started on all nodes,
 * or all nodes with specified role, in the cluster. The mediator can be
 * started with the [[DistributedPubSubExtension]] or as an ordinary actor.
 *
 * Changes are only performed in the own part of the registry and those changes
 * are versioned. Deltas are disseminated in a scalable way to other nodes with
 * a gossip protocol. The registry is eventually consistent, i.e. changes are not
 * immediately visible at other nodes, but typically they will be fully replicated
 * to all other nodes after a few seconds.
 *
 * You can send messages via the mediator on any node to registered actors on
 * any other node. There is three modes of message delivery.
 *
 * 1. [[DistributedPubSubMediator.Send]] -
 * The message will be delivered to one recipient with a matching path, if any such
 * exists in the registry. If several entries match the path the message will be sent
 * via the supplied `routingLogic` (default random) to one destination. The sender of the
 * message can specify that local affinity is preferred, i.e. the message is sent to an actor
 * in the same local actor system as the used mediator actor, if any such exists, otherwise
 * route to any other matching entry. A typical usage of this mode is private chat to one
 * other user in an instant messaging application. It can also be used for distributing
 * tasks to registered workers, like a cluster aware router where the routees dynamically
 * can register themselves.
 *
 * 2. [[DistributedPubSubMediator.SendToAll]] -
 * The message will be delivered to all recipients with a matching path. Actors with
 * the same path, without address information, can be registered on different nodes.
 * On each node there can only be one such actor, since the path is unique within one
 * local actor system. Typical usage of this mode is to broadcast messages to all replicas
 * with the same path, e.g. 3 actors on different nodes that all perform the same actions,
 * for redundancy.
 *
 * 3. [[DistributedPubSubMediator.Publish]] -
 * Actors may be registered to a named topic instead of path. This enables many subscribers
 * on each node. The message will be delivered to all subscribers of the topic. For
 * efficiency the message is sent over the wire only once per node (that has a matching topic),
 * and then delivered to all subscribers of the local topic representation. This is the
 * true pub/sub mode. A typical usage of this mode is a chat room in an instant messaging
 * application.
 *
 * You register actors to the local mediator with [[DistributedPubSubMediator.Put]] or
 * [[DistributedPubSubMediator.Subscribe]]. `Put` is used together with `Send` and
 * `SendToAll` message delivery modes. The `ActorRef` in `Put` must belong to the same
 * local actor system as the mediator. `Subscribe` is used together with `Publish`.
 * Actors are automatically removed from the registry when they are terminated, or you
 * can explicitly remove entries with [[DistributedPubSubMediator.Remove]] or
 * [[DistributedPubSubMediator.Unsubscribe]].
 *
 * Successful `Subscribe` and `Unsubscribe` is acknowledged with
 * [[DistributedPubSubMediator.SubscribeAck]] and [[DistributedPubSubMediator.UnsubscribeAck]]
 * replies.
 */
class DistributedPubSubMediator(
  role: Option[String],
  routingLogic: RoutingLogic,
  gossipInterval: FiniteDuration,
  removedTimeToLive: FiniteDuration,
  maxDeltaElements: Int)
  extends Actor with ActorLogging {

  import DistributedPubSubMediator._
  import DistributedPubSubMediator.Internal._

  val cluster = Cluster(context.system)
  import cluster.selfAddress

  require(role.forall(cluster.selfRoles.contains),
    s"This cluster member [${selfAddress}] doesn't have the role [$role]")

  val removedTimeToLiveMillis = removedTimeToLive.toMillis

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val pruneInterval: FiniteDuration = removedTimeToLive / 2
  val pruneTask = context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune)

  var registry: Map[Address, Bucket] = Map.empty.withDefault(a ⇒ Bucket(a, 0L, Map.empty))
  var nodes: Set[Address] = Set.empty

  // the version is a timestamp because it is also used when pruning removed entries
  val nextVersion = {
    var version = 0L
    () ⇒ {
      val current = System.currentTimeMillis
      version = if (current > version) current else version + 1
      version
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    require(!cluster.isTerminated, "Cluster node must not be terminated")
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster unsubscribe self
    gossipTask.cancel()
    pruneTask.cancel()
  }

  def matchingRole(m: Member): Boolean = role.forall(m.hasRole)

  def receive = {

    case Send(path, msg, localAffinity) ⇒
      registry(selfAddress).content.get(path) match {
        case Some(ValueHolder(_, Some(ref))) if localAffinity ⇒
          ref forward msg
        case _ ⇒
          val routees = (for {
            (_, bucket) ← registry
            valueHolder ← bucket.content.get(path)
            routee ← valueHolder.routee
          } yield routee).toVector

          if (routees.nonEmpty)
            Router(routingLogic, routees).route(msg, sender())
      }

    case SendToAll(path, msg, skipSenderNode) ⇒
      publish(path, msg, skipSenderNode)

    case Publish(topic, msg) ⇒
      publish(mkKey(self.path / URLEncoder.encode(topic, "utf-8")), msg)

    case Put(ref: ActorRef) ⇒
      if (ref.path.address.hasGlobalScope)
        log.warning("Registered actor must be local: [{}]", ref)
      else {
        put(mkKey(ref), Some(ref))
        context.watch(ref)
      }

    case Remove(key) ⇒
      registry(selfAddress).content.get(key) match {
        case Some(ValueHolder(_, Some(ref))) ⇒
          context.unwatch(ref)
          put(key, None)
        case _ ⇒
      }

    case msg @ Subscribe(topic, _) ⇒
      // each topic is managed by a child actor with the same name as the topic
      val encTopic = URLEncoder.encode(topic, "utf-8")
      context.child(encTopic) match {
        case Some(t) ⇒ t forward msg
        case None ⇒
          val t = context.actorOf(Props(classOf[Topic], removedTimeToLive), name = encTopic)
          t forward msg
          put(mkKey(t), Some(t))
          context.watch(t)
      }

    case msg @ Unsubscribe(topic, _) ⇒
      context.child(URLEncoder.encode(topic, "utf-8")) match {
        case Some(g) ⇒ g ! msg
        case None    ⇒ // no such topic here
      }

    case Status(otherVersions) ⇒
      // gossip chat starts with a Status message, containing the bucket versions of the other node
      val delta = collectDelta(otherVersions)
      if (delta.nonEmpty)
        sender() ! Delta(delta)
      if (otherHasNewerVersions(otherVersions))
        sender() ! Status(versions = myVersions) // it will reply with Delta

    case Delta(buckets) ⇒
      // reply from Status message in the gossip chat
      // the Delta contains potential updates (newer versions) from the other node
      // only accept deltas/buckets from known nodes, otherwise there is a risk of
      // adding back entries when nodes are removed
      if (nodes(sender().path.address)) {
        buckets foreach { b ⇒
          if (nodes(b.owner)) {
            val myBucket = registry(b.owner)
            if (b.version > myBucket.version) {
              registry += (b.owner -> myBucket.copy(version = b.version, content = myBucket.content ++ b.content))
            }
          }
        }
      }

    case GossipTick ⇒ gossip()

    case Prune      ⇒ prune()

    case Terminated(a) ⇒
      val key = mkKey(a)
      registry(selfAddress).content.get(key) match {
        case Some(ValueHolder(_, Some(`a`))) ⇒
          // remove
          put(key, None)
        case _ ⇒
      }

    case state: CurrentClusterState ⇒
      nodes = state.members.collect {
        case m if m.status != MemberStatus.Joining && matchingRole(m) ⇒ m.address
      }

    case MemberUp(m) ⇒
      if (matchingRole(m))
        nodes += m.address

    case MemberRemoved(m, _) ⇒
      if (m.address == selfAddress)
        context stop self
      else if (matchingRole(m)) {
        nodes -= m.address
        registry -= m.address
      }

    case _: MemberEvent ⇒ // not of interest

    case Count ⇒
      val count = registry.map {
        case (owner, bucket) ⇒ bucket.content.count {
          case (_, valueHolder) ⇒ valueHolder.ref.isDefined
        }
      }.sum
      sender() ! count
  }

  def publish(path: String, msg: Any, allButSelf: Boolean = false): Unit = {
    for {
      (address, bucket) ← registry
      if !(allButSelf && address == selfAddress) // if we should skip sender() node and current address == self address => skip
      valueHolder ← bucket.content.get(path)
      ref ← valueHolder.ref
    } ref forward msg
  }

  def put(key: String, valueOption: Option[ActorRef]): Unit = {
    val bucket = registry(selfAddress)
    val v = nextVersion()
    registry += (selfAddress -> bucket.copy(version = v,
      content = bucket.content + (key -> ValueHolder(v, valueOption))))
  }

  def mkKey(ref: ActorRef): String = mkKey(ref.path)

  def mkKey(path: ActorPath): String = path.toStringWithoutAddress

  def myVersions: Map[Address, Long] = registry.map { case (owner, bucket) ⇒ (owner -> bucket.version) }

  def collectDelta(otherVersions: Map[Address, Long]): immutable.Iterable[Bucket] = {
    // missing entries are represented by version 0
    val filledOtherVersions = myVersions.map { case (k, _) ⇒ k -> 0L } ++ otherVersions
    var count = 0
    filledOtherVersions.collect {
      case (owner, v) if registry(owner).version > v && count < maxDeltaElements ⇒
        val bucket = registry(owner)
        val deltaContent = bucket.content.filter {
          case (_, value) ⇒ value.version > v
        }
        count += deltaContent.size
        if (count <= maxDeltaElements)
          bucket.copy(content = deltaContent)
        else {
          // exceeded the maxDeltaElements, pick the elements with lowest versions
          val sortedContent = deltaContent.toVector.sortBy(_._2.version)
          val chunk = sortedContent.take(maxDeltaElements - (count - sortedContent.size))
          bucket.copy(content = chunk.toMap, version = chunk.last._2.version)
        }
    }
  }

  def otherHasNewerVersions(otherVersions: Map[Address, Long]): Boolean =
    otherVersions.exists {
      case (owner, v) ⇒ v > registry(owner).version
    }

  /**
   * Gossip to peer nodes.
   */
  def gossip(): Unit = selectRandomNode((nodes - selfAddress).toVector) foreach gossipTo

  def gossipTo(address: Address): Unit = {
    context.actorSelection(self.path.toStringWithAddress(address)) ! Status(versions = myVersions)
  }

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def prune(): Unit = {
    registry foreach {
      case (owner, bucket) ⇒
        val oldRemoved = bucket.content.collect {
          case (key, ValueHolder(version, None)) if (bucket.version - version > removedTimeToLiveMillis) ⇒ key
        }
        if (oldRemoved.nonEmpty)
          registry += owner -> bucket.copy(content = bucket.content -- oldRemoved)
    }
  }
}

/**
 * Extension that starts a [[DistributedPubSubMediator]] actor
 * with settings defined in config section `akka.contrib.cluster.pub-sub`.
 */
object DistributedPubSubExtension extends ExtensionId[DistributedPubSubExtension] with ExtensionIdProvider {
  override def get(system: ActorSystem): DistributedPubSubExtension = super.get(system)

  override def lookup = DistributedPubSubExtension

  override def createExtension(system: ExtendedActorSystem): DistributedPubSubExtension =
    new DistributedPubSubExtension(system)
}

class DistributedPubSubExtension(system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config.getConfig("akka.contrib.cluster.pub-sub")
  private val role: Option[String] = config.getString("role") match {
    case "" ⇒ None
    case r  ⇒ Some(r)
  }

  /**
   * Returns true if this member is not tagged with the role configured for the
   * mediator.
   */
  def isTerminated: Boolean = Cluster(system).isTerminated || !role.forall(Cluster(system).selfRoles.contains)

  /**
   * The [[DistributedPubSubMediator]]
   */
  val mediator: ActorRef = {
    if (isTerminated)
      system.deadLetters
    else {
      val routingLogic = config.getString("routing-logic") match {
        case "random"             ⇒ RandomRoutingLogic()
        case "round-robin"        ⇒ RoundRobinRoutingLogic()
        case "consistent-hashing" ⇒ ConsistentHashingRoutingLogic(system)
        case "broadcast"          ⇒ BroadcastRoutingLogic()
        case other                ⇒ throw new IllegalArgumentException(s"Unknown 'routing-logic': [$other]")
      }
      val gossipInterval = config.getDuration("gossip-interval", MILLISECONDS).millis
      val removedTimeToLive = config.getDuration("removed-time-to-live", MILLISECONDS).millis
      val maxDeltaElements = config.getInt("max-delta-elements")
      val name = config.getString("name")
      system.actorOf(DistributedPubSubMediator.props(role, routingLogic, gossipInterval, removedTimeToLive, maxDeltaElements),
        name)
    }
  }
}
