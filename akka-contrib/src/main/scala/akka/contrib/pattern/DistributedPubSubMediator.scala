/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorPath
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.cluster.MemberStatus

object DistributedPubSubMediator {
  case class Put(ref: ActorRef)
  case class Remove(path: String)
  case class Subscribe(topic: String, ref: ActorRef)
  case class Unsubscribe(topic: String, ref: ActorRef)
  case class Send(path: String, msg: Any, localAffinity: Boolean)
  case class SendToAll(path: String, msg: Any)
  case class Publish(topic: String, msg: Any)

  // Only for testing purposes, to poll/await replication
  case object Count

  /**
   * INTERNAL API
   */
  private[pattern] object Internal {
    case object Prune

    case class Bucket(
      owner: Address,
      version: Long,
      content: Map[String, ValueHolder])

    case class ValueHolder(version: Long, ref: Option[ActorRef])

    case class Status(versions: Map[Address, Long])
    case class Delta(buckets: immutable.Iterable[Bucket])

    case object GossipTick

    def roleOption(role: String): Option[String] = role match {
      case null | "" ⇒ None
      case _         ⇒ Some(role)
    }

    class Topic(emptyTimeToLive: FiniteDuration) extends Actor {
      import context.dispatcher
      val pruneInterval: FiniteDuration = emptyTimeToLive / 4
      val pruneTask = context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune)
      var pruneDeadline: Option[Deadline] = None

      var subscribers = Set.empty[ActorRef]

      override def postStop: Unit = {
        pruneTask.cancel()
      }

      def receive = {
        case Subscribe(_, ref) ⇒
          context watch ref
          subscribers += ref
          pruneDeadline = None
        case Unsubscribe(_, ref) ⇒
          context unwatch ref
          leave(ref)
        case Terminated(ref) ⇒
          leave(ref)
        case Prune ⇒
          pruneDeadline match {
            case Some(d) if d.isOverdue ⇒ context stop self
            case _                      ⇒
          }
        case msg ⇒
          subscribers foreach { _ forward msg }
      }

      def leave(ref: ActorRef): Unit = {
        subscribers -= ref
        if (subscribers.isEmpty)
          pruneDeadline = Some(Deadline.now + emptyTimeToLive)
      }
    }
  }
}

/**
 * This actor manages a registry of actor references and replicates
 * the entries to peer actors among all cluster nodes or a group of nodes
 * tagged with a specific role.
 *
 * The `DistributedPubSubMediator` is supposed to be started on all nodes,
 * or all nodes with specified role, in the cluster.
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
 * exists in the registry. If several entries match the path the message will be delivered
 * to one random destination. The sender of the message can specify that local
 * affinity is preferred, i.e. the message is sent to an actor in the same local actor
 * system as the used mediator actor, if any such exists, otherwise random to any other
 * matching entry. A typical usage of this mode is private chat to one other user in
 * an instant messaging application. It can also be used for distributing tasks to workers,
 * like a random router.
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
 * `SendToAll` message delivery modes. `Subscribe` is used together with `Publish`.
 * Actors are automatically removed from the registry when they are terminated, or you
 * can explicitly remove entries with [[DistributedPubSubMediator.Remove]] or
 * [[DistributedPubSubMediator.Unsubscribe]].
 */
class DistributedPubSubMediator(
  role: Option[String],
  gossipInterval: FiniteDuration = 1.second,
  removedTimeToLive: FiniteDuration = 2.minutes)
  extends Actor with ActorLogging {

  /**
   * Java API constructor with default values.
   */
  def this(role: String) = this(DistributedPubSubMediator.Internal.roleOption(role))

  import DistributedPubSubMediator._
  import DistributedPubSubMediator.Internal._

  val cluster = Cluster(context.system)
  import cluster.selfAddress

  role match {
    case None ⇒
    case Some(r) ⇒ require(cluster.selfRoles.contains(r),
      s"This cluster member [${selfAddress}] doesn't have the role [$role]")
  }

  val removedTimeToLiveMillis = removedTimeToLive.toMillis

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val pruneInterval: FiniteDuration = removedTimeToLive / 4
  val pruneTask = context.system.scheduler.schedule(pruneInterval, pruneInterval, self, Prune)

  var registry: Map[Address, Bucket] = Map.empty.withDefault(a ⇒ Bucket(a, 0L, Map.empty))
  var nodes: Set[Address] = Set.empty

  // the version is a timestamp because it is also used when pruning removed entries
  var version = 0L
  def nextVersion(): Long = {
    val current = System.currentTimeMillis
    version = if (current > version) current else version + 1
    version
  }

  override def preStart(): Unit = {
    super.preStart()
    require(!cluster.isTerminated, "Cluster node must not be terminated")
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop: Unit = {
    cluster unsubscribe self
    gossipTask.cancel()
    pruneTask.cancel()
  }

  def matchingRole(m: Member): Boolean = role match {
    case None    ⇒ true
    case Some(r) ⇒ m.hasRole(r)
  }

  def receive = {

    case Send(path, msg, localAffinity) ⇒
      registry(selfAddress).content.get(path) match {
        case Some(ValueHolder(_, Some(ref))) if localAffinity ⇒
          ref forward msg
        case _ ⇒
          val refs = (for {
            (_, bucket) ← registry
            valueHolder ← bucket.content.get(path)
            ref ← valueHolder.ref
          } yield ref).toVector

          refs(ThreadLocalRandom.current.nextInt(refs.size)) forward msg
      }

    case SendToAll(path, msg) ⇒
      publish(path, msg)

    case Publish(topic, msg) ⇒
      publish(mkKey(self.path / topic), msg)

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
      context.child(topic) match {
        case Some(t) ⇒ t ! msg
        case None ⇒
          val t = context.actorOf(Props(new Topic(removedTimeToLive)), name = topic)
          t ! msg
          put(mkKey(t), Some(t))
          context.watch(t)
      }

    case msg @ Unsubscribe(topic, _) ⇒
      context.child(topic) match {
        case Some(g) ⇒ g ! msg
        case None    ⇒ // no such topic here
      }

    case Status(otherVersions) ⇒
      // gossip chat starts with a Status message, containing the bucket versions of the other node
      val delta = collectDelta(otherVersions)
      if (delta.nonEmpty)
        sender ! Delta(delta)
      if (otherHasNewerVersions(otherVersions))
        sender ! Status(versions = myVersions) // it will reply with Delta

    case Delta(buckets) ⇒
      // reply from Status message in the gossip chat
      // the Delta contains potential updates (newer versions) from the other node
      if (nodes.contains(sender.path.address)) {
        buckets foreach { b ⇒
          val myBucket = registry(b.owner)
          if (b.version > myBucket.version) {
            registry += (b.owner -> myBucket.copy(version = b.version, content = myBucket.content ++ b.content))
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

    case MemberRemoved(m) ⇒
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
      sender ! count
  }

  def publish(path: String, msg: Any): Unit = {
    for {
      (_, bucket) ← registry
      valueHolder ← bucket.content.get(path)
      ref ← valueHolder.ref
    } ref forward msg
  }

  def put(key: String, valueOption: Option[ActorRef]): Unit = {
    val bucket = registry(selfAddress)
    val v = nextVersion
    registry += (selfAddress -> bucket.copy(version = v,
      content = bucket.content + (key -> ValueHolder(v, valueOption))))
  }

  def mkKey(ref: ActorRef): String = mkKey(ref.path)

  def mkKey(path: ActorPath): String = path.elements.mkString("/", "/", "")

  def myVersions: Map[Address, Long] = registry.map { case (owner, bucket) ⇒ (owner -> bucket.version) }

  def collectDelta(otherVersions: Map[Address, Long]): immutable.Iterable[Bucket] = {
    // missing entries are represented by version 0
    val filledOtherVersions = myVersions.map { case (k, _) ⇒ k -> 0L } ++ otherVersions
    filledOtherVersions.collect {
      case (owner, v) if registry(owner).version > v ⇒
        val bucket = registry(owner)
        val deltaContent = bucket.content.filter {
          case (_, value) ⇒ value.version > v
        }
        bucket.copy(content = deltaContent)
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
    context.actorFor(self.path.toStringWithAddress(address)) ! Status(versions = myVersions)
  }

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def prune(): Unit = {
    val now = System.currentTimeMillis
    registry foreach {
      case (owner, bucket) ⇒
        val oldRemoved = bucket.content.collect {
          case (key, ValueHolder(version, None)) if (now - version > removedTimeToLiveMillis) ⇒ key
        }
        if (oldRemoved.nonEmpty)
          registry += owner -> bucket.copy(content = bucket.content -- oldRemoved)
    }
  }
}
