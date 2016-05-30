/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.ddata

import java.security.MessageDigest
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.ThreadLocalRandom
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.Deploy
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.Member
import akka.cluster.UniqueAddress
import akka.serialization.SerializationExtension
import akka.util.ByteString
import com.typesafe.config.Config
import java.util.function.{ Function ⇒ JFunction }
import akka.dispatch.Dispatchers
import akka.actor.DeadLetterSuppression
import akka.cluster.ddata.Key.KeyR
import java.util.Optional

object ReplicatorSettings {

  /**
   * Create settings from the default configuration
   * `akka.cluster.distributed-data`.
   */
  def apply(system: ActorSystem): ReplicatorSettings =
    apply(system.settings.config.getConfig("akka.cluster.distributed-data"))

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.distributed-data`.
   */
  def apply(config: Config): ReplicatorSettings = {
    val dispatcher = config.getString("use-dispatcher") match {
      case "" ⇒ Dispatchers.DefaultDispatcherId
      case id ⇒ id
    }
    new ReplicatorSettings(
      role = roleOption(config.getString("role")),
      gossipInterval = config.getDuration("gossip-interval", MILLISECONDS).millis,
      notifySubscribersInterval = config.getDuration("notify-subscribers-interval", MILLISECONDS).millis,
      maxDeltaElements = config.getInt("max-delta-elements"),
      dispatcher = dispatcher,
      pruningInterval = config.getDuration("pruning-interval", MILLISECONDS).millis,
      maxPruningDissemination = config.getDuration("max-pruning-dissemination", MILLISECONDS).millis)
  }

  /**
   * INTERNAL API
   */
  private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)
}

/**
 * @param role Replicas are running on members tagged with this role.
 *   All members are used if undefined.
 * @param gossipInterval How often the Replicator should send out gossip information.
 * @param notifySubscribersInterval How often the subscribers will be notified
 *   of changes, if any.
 * @param maxDeltaElements Maximum number of entries to transfer in one
 *   gossip message when synchronizing the replicas. Next chunk will be
 *   transferred in next round of gossip.
 * @param dispatcher Id of the dispatcher to use for Replicator actors. If not
 *   specified (`""`) the default dispatcher is used.
 * @param pruningInterval How often the Replicator checks for pruning of
 *   data associated with removed cluster nodes.
 * @param maxPruningDissemination How long time it takes (worst case) to spread
 *   the data to all other replica nodes. This is used when initiating and
 *   completing the pruning process of data associated with removed cluster nodes.
 *   The time measurement is stopped when any replica is unreachable, so it should
 *   be configured to worst case in a healthy cluster.
 */
final class ReplicatorSettings(
  val role:                      Option[String],
  val gossipInterval:            FiniteDuration,
  val notifySubscribersInterval: FiniteDuration,
  val maxDeltaElements:          Int,
  val dispatcher:                String,
  val pruningInterval:           FiniteDuration,
  val maxPruningDissemination:   FiniteDuration) {

  def withRole(role: String): ReplicatorSettings = copy(role = ReplicatorSettings.roleOption(role))

  def withRole(role: Option[String]): ReplicatorSettings = copy(role = role)

  def withGossipInterval(gossipInterval: FiniteDuration): ReplicatorSettings =
    copy(gossipInterval = gossipInterval)

  def withNotifySubscribersInterval(notifySubscribersInterval: FiniteDuration): ReplicatorSettings =
    copy(notifySubscribersInterval = notifySubscribersInterval)

  def withMaxDeltaElements(maxDeltaElements: Int): ReplicatorSettings =
    copy(maxDeltaElements = maxDeltaElements)

  def withDispatcher(dispatcher: String): ReplicatorSettings = {
    val d = dispatcher match {
      case "" ⇒ Dispatchers.DefaultDispatcherId
      case id ⇒ id
    }
    copy(dispatcher = d)
  }

  def withPruning(pruningInterval: FiniteDuration, maxPruningDissemination: FiniteDuration): ReplicatorSettings =
    copy(pruningInterval = pruningInterval, maxPruningDissemination = maxPruningDissemination)

  private def copy(
    role:                      Option[String] = role,
    gossipInterval:            FiniteDuration = gossipInterval,
    notifySubscribersInterval: FiniteDuration = notifySubscribersInterval,
    maxDeltaElements:          Int            = maxDeltaElements,
    dispatcher:                String         = dispatcher,
    pruningInterval:           FiniteDuration = pruningInterval,
    maxPruningDissemination:   FiniteDuration = maxPruningDissemination): ReplicatorSettings =
    new ReplicatorSettings(role, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher,
      pruningInterval, maxPruningDissemination)
}

object Replicator {

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Replicator]] actor.
   */
  def props(settings: ReplicatorSettings): Props =
    Props(new Replicator(settings)).withDeploy(Deploy.local).withDispatcher(settings.dispatcher)

  sealed trait ReadConsistency {
    def timeout: FiniteDuration
  }
  case object ReadLocal extends ReadConsistency {
    override def timeout: FiniteDuration = Duration.Zero
  }
  final case class ReadFrom(n: Int, timeout: FiniteDuration) extends ReadConsistency {
    require(n >= 2, "ReadFrom n must be >= 2, use ReadLocal for n=1")
  }
  final case class ReadMajority(timeout: FiniteDuration) extends ReadConsistency
  final case class ReadAll(timeout: FiniteDuration) extends ReadConsistency

  sealed trait WriteConsistency {
    def timeout: FiniteDuration
  }
  case object WriteLocal extends WriteConsistency {
    override def timeout: FiniteDuration = Duration.Zero
  }
  final case class WriteTo(n: Int, timeout: FiniteDuration) extends WriteConsistency {
    require(n >= 2, "WriteTo n must be >= 2, use WriteLocal for n=1")
  }
  final case class WriteMajority(timeout: FiniteDuration) extends WriteConsistency
  final case class WriteAll(timeout: FiniteDuration) extends WriteConsistency

  /**
   * Java API: The `ReadLocal` instance
   */
  def readLocal = ReadLocal

  /**
   * Java API: The `WriteLocal` instance
   */
  def writeLocal = WriteLocal

  /**
   * INTERNAL API
   */
  private[akka] case object GetKeyIds

  /**
   * INTERNAL API
   */
  private[akka] final case class GetKeyIdsResult(keyIds: Set[String]) {
    /**
     * Java API
     */
    def getKeyIds: java.util.Set[String] = {
      import scala.collection.JavaConverters._
      keyIds.asJava
    }
  }

  sealed trait Command[A <: ReplicatedData] {
    def key: Key[A]
  }

  /**
   * Send this message to the local `Replicator` to retrieve a data value for the
   * given `key`. The `Replicator` will reply with one of the [[GetResponse]] messages.
   *
   * The optional `request` context is included in the reply messages. This is a convenient
   * way to pass contextual information (e.g. original sender) without having to use `ask`
   * or maintain local correlation data structures.
   */
  final case class Get[A <: ReplicatedData](key: Key[A], consistency: ReadConsistency, request: Option[Any] = None)
    extends Command[A] with ReplicatorMessage {
    /**
     * Java API: `Get` value from local `Replicator`, i.e. `ReadLocal` consistency.
     */
    def this(key: Key[A], consistency: ReadConsistency) = this(key, consistency, None)

    /**
     * Java API: `Get` value from local `Replicator`, i.e. `ReadLocal` consistency.
     */
    def this(key: Key[A], consistency: ReadConsistency, request: Optional[Any]) =
      this(key, consistency, Option(request.orElse(null)))

  }
  sealed abstract class GetResponse[A <: ReplicatedData] extends NoSerializationVerificationNeeded {
    def key: Key[A]
    def request: Option[Any]

    /** Java API */
    def getRequest: Optional[Any] = Optional.ofNullable(request.orNull)
  }
  /**
   * Reply from `Get`. The data value is retrieved with [[#get]] using the typed key.
   */
  final case class GetSuccess[A <: ReplicatedData](key: Key[A], request: Option[Any])(data: A)
    extends GetResponse[A] with ReplicatorMessage {

    /**
     * The data value, with correct type.
     * Scala pattern matching cannot infer the type from the `key` parameter.
     */
    def get[T <: ReplicatedData](key: Key[T]): T = {
      require(key == this.key, "wrong key used, must use contained key")
      data.asInstanceOf[T]
    }

    /**
     * The data value. Use [[#get]] to get the fully typed value.
     */
    def dataValue: A = data
  }
  final case class NotFound[A <: ReplicatedData](key: Key[A], request: Option[Any])
    extends GetResponse[A] with ReplicatorMessage
  /**
   * The [[Get]] request could not be fulfill according to the given
   * [[ReadConsistency consistency level]] and [[ReadConsistency#timeout timeout]].
   */
  final case class GetFailure[A <: ReplicatedData](key: Key[A], request: Option[Any])
    extends GetResponse[A] with ReplicatorMessage

  /**
   * Register a subscriber that will be notified with a [[Changed]] message
   * when the value of the given `key` is changed. Current value is also
   * sent as a [[Changed]] message to a new subscriber.
   *
   * Subscribers will be notified periodically with the configured `notify-subscribers-interval`,
   * and it is also possible to send an explicit `FlushChanges` message to
   * the `Replicator` to notify the subscribers immediately.
   *
   * The subscriber will automatically be unregistered if it is terminated.
   *
   * If the key is deleted the subscriber is notified with a [[DataDeleted]]
   * message.
   */
  final case class Subscribe[A <: ReplicatedData](key: Key[A], subscriber: ActorRef) extends ReplicatorMessage
  /**
   * Unregister a subscriber.
   * @see [[Replicator.Subscribe]]
   */
  final case class Unsubscribe[A <: ReplicatedData](key: Key[A], subscriber: ActorRef) extends ReplicatorMessage
  /**
   * The data value is retrieved with [[#get]] using the typed key.
   * @see [[Replicator.Subscribe]]
   */
  final case class Changed[A <: ReplicatedData](key: Key[A])(data: A) extends ReplicatorMessage {
    /**
     * The data value, with correct type.
     * Scala pattern matching cannot infer the type from the `key` parameter.
     */
    def get[T <: ReplicatedData](key: Key[T]): T = {
      require(key == this.key, "wrong key used, must use contained key")
      data.asInstanceOf[T]
    }

    /**
     * The data value. Use [[#get]] to get the fully typed value.
     */
    def dataValue: A = data
  }

  object Update {

    /**
     * Modify value of local `Replicator` and replicate with given `writeConsistency`.
     *
     * The current value for the `key` is passed to the `modify` function.
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     *
     * The optional `request` context is included in the reply messages. This is a convenient
     * way to pass contextual information (e.g. original sender) without having to use `ask`
     * or local correlation data structures.
     */
    def apply[A <: ReplicatedData](
      key: Key[A], initial: A, writeConsistency: WriteConsistency,
      request: Option[Any] = None)(modify: A ⇒ A): Update[A] =
      Update(key, writeConsistency, request)(modifyWithInitial(initial, modify))

    private def modifyWithInitial[A <: ReplicatedData](initial: A, modify: A ⇒ A): Option[A] ⇒ A = {
      case Some(data) ⇒ modify(data)
      case None       ⇒ modify(initial)
    }
  }
  /**
   * Send this message to the local `Replicator` to update a data value for the
   * given `key`. The `Replicator` will reply with one of the [[UpdateResponse]] messages.
   *
   * Note that the [[Replicator.Update$ companion]] object provides `apply` functions for convenient
   * construction of this message.
   *
   * The current data value for the `key` is passed as parameter to the `modify` function.
   * It is `None` if there is no value for the `key`, and otherwise `Some(data)`. The function
   * is supposed to return the new value of the data, which will then be replicated according to
   * the given `writeConsistency`.
   *
   * The `modify` function is called by the `Replicator` actor and must therefore be a pure
   * function that only uses the data parameter and stable fields from enclosing scope. It must
   * for example not access `sender()` reference of an enclosing actor.
   */
  final case class Update[A <: ReplicatedData](key: Key[A], writeConsistency: WriteConsistency,
                                               request: Option[Any])(val modify: Option[A] ⇒ A)
    extends Command[A] with NoSerializationVerificationNeeded {

    /**
     * Java API: Modify value of local `Replicator` and replicate with given `writeConsistency`.
     *
     * The current value for the `key` is passed to the `modify` function.
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     */
    def this(
      key: Key[A], initial: A, writeConsistency: WriteConsistency, modify: JFunction[A, A]) =
      this(key, writeConsistency, None)(Update.modifyWithInitial(initial, data ⇒ modify.apply(data)))

    /**
     * Java API: Modify value of local `Replicator` and replicate with given `writeConsistency`.
     *
     * The current value for the `key` is passed to the `modify` function.
     * If there is no current data value for the `key` the `initial` value will be
     * passed to the `modify` function.
     *
     * The optional `request` context is included in the reply messages. This is a convenient
     * way to pass contextual information (e.g. original sender) without having to use `ask`
     * or local correlation data structures.
     */
    def this(
      key: Key[A], initial: A, writeConsistency: WriteConsistency, request: Optional[Any], modify: JFunction[A, A]) =
      this(key, writeConsistency, Option(request.orElse(null)))(Update.modifyWithInitial(initial, data ⇒ modify.apply(data)))

  }

  sealed abstract class UpdateResponse[A <: ReplicatedData] extends NoSerializationVerificationNeeded {
    def key: Key[A]
    def request: Option[Any]

    /** Java API */
    def getRequest: Optional[Any] = Optional.ofNullable(request.orNull)
  }
  final case class UpdateSuccess[A <: ReplicatedData](key: Key[A], request: Option[Any]) extends UpdateResponse[A]
  sealed abstract class UpdateFailure[A <: ReplicatedData] extends UpdateResponse[A]

  /**
   * The direct replication of the [[Update]] could not be fulfill according to
   * the given [[WriteConsistency consistency level]] and
   * [[WriteConsistency#timeout timeout]].
   *
   * The `Update` was still performed locally and possibly replicated to some nodes.
   * It will eventually be disseminated to other replicas, unless the local replica
   * crashes before it has been able to communicate with other replicas.
   */
  final case class UpdateTimeout[A <: ReplicatedData](key: Key[A], request: Option[Any]) extends UpdateFailure[A]
  /**
   * If the `modify` function of the [[Update]] throws an exception the reply message
   * will be this `ModifyFailure` message. The original exception is included as `cause`.
   */
  final case class ModifyFailure[A <: ReplicatedData](key: Key[A], errorMessage: String, cause: Throwable, request: Option[Any])
    extends UpdateFailure[A] {
    override def toString: String = s"ModifyFailure [$key]: $errorMessage"
  }

  /**
   * Send this message to the local `Replicator` to delete a data value for the
   * given `key`. The `Replicator` will reply with one of the [[DeleteResponse]] messages.
   */
  final case class Delete[A <: ReplicatedData](key: Key[A], consistency: WriteConsistency) extends Command[A]

  sealed trait DeleteResponse[A <: ReplicatedData] {
    def key: Key[A]
  }
  final case class DeleteSuccess[A <: ReplicatedData](key: Key[A]) extends DeleteResponse[A]
  final case class ReplicationDeleteFailure[A <: ReplicatedData](key: Key[A]) extends DeleteResponse[A]
  final case class DataDeleted[A <: ReplicatedData](key: Key[A])
    extends RuntimeException with NoStackTrace with DeleteResponse[A] {
    override def toString: String = s"DataDeleted [$key]"
  }

  /**
   * Get current number of replicas, including the local replica.
   * Will reply to sender with [[ReplicaCount]].
   */
  final case object GetReplicaCount

  /**
   * Java API: The `GetReplicaCount` instance
   */
  def getReplicaCount = GetReplicaCount

  /**
   * Current number of replicas. Reply to `GetReplicaCount`.
   */
  final case class ReplicaCount(n: Int)

  /**
   * Notify subscribers of changes now, otherwise they will be notified periodically
   * with the configured `notify-subscribers-interval`.
   */
  case object FlushChanges

  /**
   * Java API: The `FlushChanges` instance
   */
  def flushChanges = FlushChanges

  /**
   * Marker trait for remote messages serialized by
   * [[akka.cluster.ddata.protobuf.ReplicatorMessageSerializer]].
   */
  trait ReplicatorMessage extends Serializable

  /**
   * INTERNAL API
   */
  private[akka] object Internal {

    case object GossipTick
    case object RemovedNodePruningTick
    case object ClockTick
    final case class Write(key: String, envelope: DataEnvelope) extends ReplicatorMessage
    case object WriteAck extends ReplicatorMessage with DeadLetterSuppression
    final case class Read(key: String) extends ReplicatorMessage
    final case class ReadResult(envelope: Option[DataEnvelope]) extends ReplicatorMessage with DeadLetterSuppression
    final case class ReadRepair(key: String, envelope: DataEnvelope)
    case object ReadRepairAck

    // Gossip Status message contains SHA-1 digests of the data to determine when
    // to send the full data
    type Digest = ByteString
    val DeletedDigest: Digest = ByteString.empty
    val LazyDigest: Digest = ByteString(0)
    val NotFoundDigest: Digest = ByteString(-1)

    final case class DataEnvelope(
      data:    ReplicatedData,
      pruning: Map[UniqueAddress, PruningState] = Map.empty)
      extends ReplicatorMessage {

      import PruningState._

      def needPruningFrom(removedNode: UniqueAddress): Boolean =
        data match {
          case r: RemovedNodePruning ⇒ r.needPruningFrom(removedNode)
          case _                     ⇒ false
        }

      def initRemovedNodePruning(removed: UniqueAddress, owner: UniqueAddress): DataEnvelope = {
        copy(pruning = pruning.updated(removed, PruningState(owner, PruningInitialized(Set.empty))))
      }

      def prune(from: UniqueAddress): DataEnvelope = {
        data match {
          case dataWithRemovedNodePruning: RemovedNodePruning ⇒
            require(pruning.contains(from))
            val to = pruning(from).owner
            val prunedData = dataWithRemovedNodePruning.prune(from, to)
            copy(data = prunedData, pruning = pruning.updated(from, PruningState(to, PruningPerformed)))
          case _ ⇒ this
        }

      }

      def merge(other: DataEnvelope): DataEnvelope =
        if (other.data == DeletedData) DeletedEnvelope
        else {
          var mergedRemovedNodePruning = other.pruning
          for ((key, thisValue) ← pruning) {
            mergedRemovedNodePruning.get(key) match {
              case None ⇒ mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue)
              case Some(thatValue) ⇒
                mergedRemovedNodePruning = mergedRemovedNodePruning.updated(key, thisValue merge thatValue)
            }
          }

          // cleanup both sides before merging, `merge((otherData: ReplicatedData)` will cleanup other.data
          copy(data = cleaned(data, mergedRemovedNodePruning), pruning = mergedRemovedNodePruning).merge(other.data)
        }

      def merge(otherData: ReplicatedData): DataEnvelope =
        if (otherData == DeletedData) DeletedEnvelope
        else copy(data = data merge cleaned(otherData, pruning).asInstanceOf[data.T])

      private def cleaned(c: ReplicatedData, p: Map[UniqueAddress, PruningState]): ReplicatedData = p.foldLeft(c) {
        case (c: RemovedNodePruning, (removed, PruningState(_, PruningPerformed))) ⇒
          if (c.needPruningFrom(removed)) c.pruningCleanup(removed) else c
        case (c, _) ⇒ c
      }

      def addSeen(node: Address): DataEnvelope = {
        var changed = false
        val newRemovedNodePruning = pruning.map {
          case (removed, pruningState) ⇒
            val newPruningState = pruningState.addSeen(node)
            changed = (newPruningState ne pruningState) || changed
            (removed, newPruningState)
        }
        if (changed) copy(pruning = newRemovedNodePruning)
        else this
      }
    }

    val DeletedEnvelope = DataEnvelope(DeletedData)

    case object DeletedData extends ReplicatedData with ReplicatedDataSerialization {
      type T = ReplicatedData
      override def merge(that: ReplicatedData): ReplicatedData = DeletedData
    }

    final case class Status(digests: Map[String, Digest], chunk: Int, totChunks: Int) extends ReplicatorMessage {
      override def toString: String =
        (digests.map {
          case (key, bytes) ⇒ key + " -> " + bytes.map(byte ⇒ f"$byte%02x").mkString("")
        }).mkString("Status(", ", ", ")")
    }
    final case class Gossip(updatedData: Map[String, DataEnvelope], sendBack: Boolean) extends ReplicatorMessage

  }
}

/**
 * A replicated in-memory data store supporting low latency and high availability
 * requirements.
 *
 * The `Replicator` actor takes care of direct replication and gossip based
 * dissemination of Conflict Free Replicated Data Types (CRDTs) to replicas in the
 * the cluster.
 * The data types must be convergent CRDTs and implement [[ReplicatedData]], i.e.
 * they provide a monotonic merge function and the state changes always converge.
 *
 * You can use your own custom [[ReplicatedData]] types, and several types are provided
 * by this package, such as:
 *
 * <ul>
 * <li>Counters: [[GCounter]], [[PNCounter]]</li>
 * <li>Registers: [[LWWRegister]], [[Flag]]</li>
 * <li>Sets: [[GSet]], [[ORSet]]</li>
 * <li>Maps: [[ORMap]], [[ORMultiMap]], [[LWWMap]], [[PNCounterMap]]</li>
 * </ul>
 *
 * For good introduction to the CRDT subject watch the
 * <a href="http://www.ustream.tv/recorded/61448875">The Final Causal Frontier</a>
 * and <a href="http://vimeo.com/43903960">Eventually Consistent Data Structures</a>
 * talk by Sean Cribbs and and the
 * <a href="http://research.microsoft.com/apps/video/dl.aspx?id=153540">talk by Mark Shapiro</a>
 * and read the excellent paper <a href="http://hal.upmc.fr/docs/00/55/55/88/PDF/techreport.pdf">
 * A comprehensive study of Convergent and Commutative Replicated Data Types</a>
 * by Mark Shapiro et. al.
 *
 * The `Replicator` actor must be started on each node in the cluster, or group of
 * nodes tagged with a specific role. It communicates with other `Replicator` instances
 * with the same path (without address) that are running on other nodes . For convenience it
 * can be used with the [[DistributedData]] extension.
 *
 * == Update ==
 *
 * To modify and replicate a [[ReplicatedData]] value you send a [[Replicator.Update]] message
 * to the local `Replicator`.
 * The current data value for the `key` of the `Update` is passed as parameter to the `modify`
 * function of the `Update`. The function is supposed to return the new value of the data, which
 * will then be replicated according to the given consistency level.
 *
 * The `modify` function is called by the `Replicator` actor and must therefore be a pure
 * function that only uses the data parameter and stable fields from enclosing scope. It must
 * for example not access `sender()` reference of an enclosing actor.
 *
 * `Update` is intended to only be sent from an actor running in same local `ActorSystem` as
 * the `Replicator`, because the `modify` function is typically not serializable.
 *
 * You supply a write consistency level which has the following meaning:
 * <ul>
 * <li>`WriteLocal` the value will immediately only be written to the local replica,
 *     and later disseminated with gossip</li>
 * <li>`WriteTo(n)` the value will immediately be written to at least `n` replicas,
 *     including the local replica</li>
 * <li>`WriteMajority` the value will immediately be written to a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`WriteAll` the value will immediately be written to all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Update` a [[Replicator.UpdateSuccess]] is sent to the sender of the
 * `Update` if the value was successfully replicated according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.UpdateFailure]] subclass is
 * sent back. Note that a [[Replicator.UpdateTimeout]] reply does not mean that the update completely failed
 * or was rolled back. It may still have been replicated to some nodes, and will eventually
 * be replicated to all nodes with the gossip protocol.
 *
 * You will always see your own writes. For example if you send two `Update` messages
 * changing the value of the same `key`, the `modify` function of the second message will
 * see the change that was performed by the first `Update` message.
 *
 * In the `Update` message you can pass an optional request context, which the `Replicator`
 * does not care about, but is included in the reply messages. This is a convenient
 * way to pass contextual information (e.g. original sender) without having to use `ask`
 * or local correlation data structures.
 *
 * == Get ==
 *
 * To retrieve the current value of a data you send [[Replicator.Get]] message to the
 * `Replicator`. You supply a consistency level which has the following meaning:
 * <ul>
 * <li>`ReadLocal` the value will only be read from the local replica</li>
 * <li>`ReadFrom(n)` the value will be read and merged from `n` replicas,
 *     including the local replica</li>
 * <li>`ReadMajority` the value will be read and merged from a majority of replicas, i.e.
 *     at least `N/2 + 1` replicas, where N is the number of nodes in the cluster
 *     (or cluster role group)</li>
 * <li>`ReadAll` the value will be read and merged from all nodes in the cluster
 *     (or all nodes in the cluster role group)</li>
 * </ul>
 *
 * As reply of the `Get` a [[Replicator.GetSuccess]] is sent to the sender of the
 * `Get` if the value was successfully retrieved according to the supplied consistency
 * level within the supplied timeout. Otherwise a [[Replicator.GetFailure]] is sent.
 * If the key does not exist the reply will be [[Replicator.NotFound]].
 *
 * You will always read your own writes. For example if you send a `Update` message
 * followed by a `Get` of the same `key` the `Get` will retrieve the change that was
 * performed by the preceding `Update` message. However, the order of the reply messages are
 * not defined, i.e. in the previous example you may receive the `GetSuccess` before
 * the `UpdateSuccess`.
 *
 * In the `Get` message you can pass an optional request context in the same way as for the
 * `Update` message, described above. For example the original sender can be passed and replied
 * to after receiving and transforming `GetSuccess`.
 *
 * == Subscribe ==
 *
 * You may also register interest in change notifications by sending [[Replicator.Subscribe]]
 * message to the `Replicator`. It will send [[Replicator.Changed]] messages to the registered
 * subscriber when the data for the subscribed key is updated. Subscribers will be notified
 * periodically with the configured `notify-subscribers-interval`, and it is also possible to
 * send an explicit `Replicator.FlushChanges` message to the `Replicator` to notify the subscribers
 * immediately.
 *
 * The subscriber is automatically removed if the subscriber is terminated. A subscriber can
 * also be deregistered with the [[Replicator.Unsubscribe]] message.
 *
 * == Delete ==
 *
 * A data entry can be deleted by sending a [[Replicator.Delete]] message to the local
 * local `Replicator`. As reply of the `Delete` a [[Replicator.DeleteSuccess]] is sent to
 * the sender of the `Delete` if the value was successfully deleted according to the supplied
 * consistency level within the supplied timeout. Otherwise a [[Replicator.ReplicationDeleteFailure]]
 * is sent. Note that `ReplicationDeleteFailure` does not mean that the delete completely failed or
 * was rolled back. It may still have been replicated to some nodes, and may eventually be replicated
 * to all nodes.
 *
 * A deleted key cannot be reused again, but it is still recommended to delete unused
 * data entries because that reduces the replication overhead when new nodes join the cluster.
 * Subsequent `Delete`, `Update` and `Get` requests will be replied with [[Replicator.DataDeleted]].
 * Subscribers will receive [[Replicator.DataDeleted]].
 *
 * == CRDT Garbage ==
 *
 * One thing that can be problematic with CRDTs is that some data types accumulate history (garbage).
 * For example a `GCounter` keeps track of one counter per node. If a `GCounter` has been updated
 * from one node it will associate the identifier of that node forever. That can become a problem
 * for long running systems with many cluster nodes being added and removed. To solve this problem
 * the `Replicator` performs pruning of data associated with nodes that have been removed from the
 * cluster. Data types that need pruning have to implement [[RemovedNodePruning]]. The pruning consists
 * of several steps:
 * <ol>
 * <li>When a node is removed from the cluster it is first important that all updates that were
 * done by that node are disseminated to all other nodes. The pruning will not start before the
 * `maxPruningDissemination` duration has elapsed. The time measurement is stopped when any
 * replica is unreachable, so it should be configured to worst case in a healthy cluster.</li>
 * <li>The nodes are ordered by their address and the node ordered first is called leader.
 * The leader initiates the pruning by adding a `PruningInitialized` marker in the data envelope.
 * This is gossiped to all other nodes and they mark it as seen when they receive it.</li>
 * <li>When the leader sees that all other nodes have seen the `PruningInitialized` marker
 * the leader performs the pruning and changes the marker to `PruningPerformed` so that nobody
 * else will redo the pruning. The data envelope with this pruning state is a CRDT itself.
 * The pruning is typically performed by "moving" the part of the data associated with
 * the removed node to the leader node. For example, a `GCounter` is a `Map` with the node as key
 * and the counts done by that node as value. When pruning the value of the removed node is
 * moved to the entry owned by the leader node. See [[RemovedNodePruning#prune]].</li>
 * <li>Thereafter the data is always cleared from parts associated with the removed node so that
 * it does not come back when merging. See [[RemovedNodePruning#pruningCleanup]]</li>
 * <li>After another `maxPruningDissemination` duration after pruning the last entry from the
 * removed node the `PruningPerformed` markers in the data envelope are collapsed into a
 * single tombstone entry, for efficiency. Clients may continue to use old data and therefore
 * all data are always cleared from parts associated with tombstoned nodes. </li>
 * </ol>
 */
final class Replicator(settings: ReplicatorSettings) extends Actor with ActorLogging {

  import Replicator._
  import Replicator.Internal._
  import PruningState._
  import settings._

  val cluster = Cluster(context.system)
  val selfAddress = cluster.selfAddress
  val selfUniqueAddress = cluster.selfUniqueAddress

  require(!cluster.isTerminated, "Cluster node must not be terminated")
  require(
    role.forall(cluster.selfRoles.contains),
    s"This cluster member [${selfAddress}] doesn't have the role [$role]")

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val notifyTask = context.system.scheduler.schedule(notifySubscribersInterval, notifySubscribersInterval, self, FlushChanges)
  val pruningTask = context.system.scheduler.schedule(pruningInterval, pruningInterval, self, RemovedNodePruningTick)
  val clockTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, ClockTick)

  val serializer = SerializationExtension(context.system).serializerFor(classOf[DataEnvelope])
  val maxPruningDisseminationNanos = maxPruningDissemination.toNanos

  // cluster nodes, doesn't contain selfAddress
  var nodes: Set[Address] = Set.empty

  // nodes removed from cluster, to be pruned, and tombstoned
  var removedNodes: Map[UniqueAddress, Long] = Map.empty
  var pruningPerformed: Map[UniqueAddress, Long] = Map.empty
  var tombstoneNodes: Set[UniqueAddress] = Set.empty

  var leader: Option[Address] = None
  def isLeader: Boolean = leader.exists(_ == selfAddress)

  // for pruning timeouts are based on clock that is only increased when all nodes are reachable
  var previousClockTime = System.nanoTime()
  var allReachableClockTime = 0L
  var unreachable = Set.empty[Address]

  // the actual data
  var dataEntries = Map.empty[String, (DataEnvelope, Digest)]
  // keys that have changed, Changed event published to subscribers on FlushChanges
  var changed = Set.empty[String]

  // for splitting up gossip in chunks
  var statusCount = 0L
  var statusTotChunks = 0

  val subscribers = new mutable.HashMap[String, mutable.Set[ActorRef]] with mutable.MultiMap[String, ActorRef]
  val newSubscribers = new mutable.HashMap[String, mutable.Set[ActorRef]] with mutable.MultiMap[String, ActorRef]
  var subscriptionKeys = Map.empty[String, KeyR]

  override def preStart(): Unit = {
    val leaderChangedClass = if (role.isDefined) classOf[RoleLeaderChanged] else classOf[LeaderChanged]
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[ReachabilityEvent], leaderChangedClass)
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    gossipTask.cancel()
    notifyTask.cancel()
    pruningTask.cancel()
    clockTask.cancel()
  }

  def matchingRole(m: Member): Boolean = role.forall(m.hasRole)

  def receive = normalReceive

  val normalReceive: Receive = {
    case Get(key, consistency, req)             ⇒ receiveGet(key, consistency, req)
    case u @ Update(key, writeC, req)           ⇒ receiveUpdate(key, u.modify, writeC, req)
    case Read(key)                              ⇒ receiveRead(key)
    case Write(key, envelope)                   ⇒ receiveWrite(key, envelope)
    case ReadRepair(key, envelope)              ⇒ receiveReadRepair(key, envelope)
    case FlushChanges                           ⇒ receiveFlushChanges()
    case GossipTick                             ⇒ receiveGossipTick()
    case ClockTick                              ⇒ receiveClockTick()
    case Status(otherDigests, chunk, totChunks) ⇒ receiveStatus(otherDigests, chunk, totChunks)
    case Gossip(updatedData, sendBack)          ⇒ receiveGossip(updatedData, sendBack)
    case Subscribe(key, subscriber)             ⇒ receiveSubscribe(key, subscriber)
    case Unsubscribe(key, subscriber)           ⇒ receiveUnsubscribe(key, subscriber)
    case Terminated(ref)                        ⇒ receiveTerminated(ref)
    case MemberUp(m)                            ⇒ receiveMemberUp(m)
    case MemberRemoved(m, _)                    ⇒ receiveMemberRemoved(m)
    case _: MemberEvent                         ⇒ // not of interest
    case UnreachableMember(m)                   ⇒ receiveUnreachable(m)
    case ReachableMember(m)                     ⇒ receiveReachable(m)
    case LeaderChanged(leader)                  ⇒ receiveLeaderChanged(leader, None)
    case RoleLeaderChanged(role, leader)        ⇒ receiveLeaderChanged(leader, Some(role))
    case GetKeyIds                              ⇒ receiveGetKeyIds()
    case Delete(key, consistency)               ⇒ receiveDelete(key, consistency)
    case RemovedNodePruningTick                 ⇒ receiveRemovedNodePruningTick()
    case GetReplicaCount                        ⇒ receiveGetReplicaCount()
  }

  def receiveGet(key: KeyR, consistency: ReadConsistency, req: Option[Any]): Unit = {
    val localValue = getData(key.id)
    log.debug("Received Get for key [{}], local data [{}]", key, localValue)
    if (isLocalGet(consistency)) {
      val reply = localValue match {
        case Some(DataEnvelope(DeletedData, _)) ⇒ DataDeleted(key)
        case Some(DataEnvelope(data, _))        ⇒ GetSuccess(key, req)(data)
        case None                               ⇒ NotFound(key, req)
      }
      sender() ! reply
    } else
      context.actorOf(ReadAggregator.props(key, consistency, req, nodes, localValue, sender())
        .withDispatcher(context.props.dispatcher))
  }

  def isLocalGet(readConsistency: ReadConsistency): Boolean =
    readConsistency match {
      case ReadLocal                    ⇒ true
      case _: ReadMajority | _: ReadAll ⇒ nodes.isEmpty
      case _                            ⇒ false
    }

  def receiveRead(key: String): Unit = {
    sender() ! ReadResult(getData(key))
  }

  def isLocalSender(): Boolean = !sender().path.address.hasGlobalScope

  def receiveUpdate(key: KeyR, modify: Option[ReplicatedData] ⇒ ReplicatedData,
                    writeConsistency: WriteConsistency, req: Option[Any]): Unit = {
    val localValue = getData(key.id)
    Try {
      localValue match {
        case Some(DataEnvelope(DeletedData, _)) ⇒ throw new DataDeleted(key)
        case Some(envelope @ DataEnvelope(existing, _)) ⇒
          existing.merge(modify(Some(existing)).asInstanceOf[existing.T])
        case None ⇒ modify(None)
      }
    } match {
      case Success(newData) ⇒
        log.debug("Received Update for key [{}], old data [{}], new data [{}]", key, localValue, newData)
        val envelope = DataEnvelope(pruningCleanupTombstoned(newData))
        setData(key.id, envelope)
        if (isLocalUpdate(writeConsistency))
          sender() ! UpdateSuccess(key, req)
        else
          context.actorOf(WriteAggregator.props(key, envelope, writeConsistency, req, nodes, sender())
            .withDispatcher(context.props.dispatcher))
      case Failure(e: DataDeleted[_]) ⇒
        log.debug("Received Update for deleted key [{}]", key)
        sender() ! e
      case Failure(e) ⇒
        log.debug("Received Update for key [{}], failed: {}", key, e.getMessage)
        sender() ! ModifyFailure(key, "Update failed: " + e.getMessage, e, req)
    }
  }

  def isLocalUpdate(writeConsistency: WriteConsistency): Boolean =
    writeConsistency match {
      case WriteLocal                     ⇒ true
      case _: WriteMajority | _: WriteAll ⇒ nodes.isEmpty
      case _                              ⇒ false
    }

  def receiveWrite(key: String, envelope: DataEnvelope): Unit = {
    write(key, envelope)
    sender() ! WriteAck
  }

  def write(key: String, writeEnvelope: DataEnvelope): Unit =
    getData(key) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒ // already deleted
      case Some(envelope @ DataEnvelope(existing, _)) ⇒
        if (existing.getClass == writeEnvelope.data.getClass || writeEnvelope.data == DeletedData) {
          val merged = envelope.merge(pruningCleanupTombstoned(writeEnvelope)).addSeen(selfAddress)
          setData(key, merged)
        } else {
          log.warning(
            "Wrong type for writing [{}], existing type [{}], got [{}]",
            key, existing.getClass.getName, writeEnvelope.data.getClass.getName)
        }
      case None ⇒
        setData(key, pruningCleanupTombstoned(writeEnvelope).addSeen(selfAddress))
    }

  def receiveReadRepair(key: String, writeEnvelope: DataEnvelope): Unit = {
    write(key, writeEnvelope)
    sender() ! ReadRepairAck
  }

  def receiveGetKeyIds(): Unit = {
    val keys: Set[String] = dataEntries.collect {
      case (key, (DataEnvelope(data, _), _)) if data != DeletedData ⇒ key
    }(collection.breakOut)
    sender() ! GetKeyIdsResult(keys)
  }

  def receiveDelete(key: KeyR, consistency: WriteConsistency): Unit = {
    getData(key.id) match {
      case Some(DataEnvelope(DeletedData, _)) ⇒
        // already deleted
        sender() ! DataDeleted(key)
      case _ ⇒
        setData(key.id, DeletedEnvelope)
        if (isLocalUpdate(consistency))
          sender() ! DeleteSuccess(key)
        else
          context.actorOf(WriteAggregator.props(key, DeletedEnvelope, consistency, None, nodes, sender())
            .withDispatcher(context.props.dispatcher))
    }
  }

  def setData(key: String, envelope: DataEnvelope): Unit = {
    val dig =
      if (subscribers.contains(key) && !changed.contains(key)) {
        val oldDigest = getDigest(key)
        val dig = digest(envelope)
        if (dig != oldDigest)
          changed += key // notify subscribers, later
        dig
      } else if (envelope.data == DeletedData) DeletedDigest
      else LazyDigest

    dataEntries = dataEntries.updated(key, (envelope, dig))
  }

  def getDigest(key: String): Digest = {
    dataEntries.get(key) match {
      case Some((envelope, LazyDigest)) ⇒
        val d = digest(envelope)
        dataEntries = dataEntries.updated(key, (envelope, d))
        d
      case Some((_, digest)) ⇒ digest
      case None              ⇒ NotFoundDigest
    }
  }

  def digest(envelope: DataEnvelope): Digest =
    if (envelope.data == DeletedData) DeletedDigest
    else {
      val bytes = serializer.toBinary(envelope)
      ByteString.fromArray(MessageDigest.getInstance("SHA-1").digest(bytes))
    }

  def getData(key: String): Option[DataEnvelope] = dataEntries.get(key).map { case (envelope, _) ⇒ envelope }

  def receiveFlushChanges(): Unit = {
    def notify(keyId: String, subs: mutable.Set[ActorRef]): Unit = {
      val key = subscriptionKeys(keyId)
      getData(keyId) match {
        case Some(envelope) ⇒
          val msg = if (envelope.data == DeletedData) DataDeleted(key) else Changed(key)(envelope.data)
          subs.foreach { _ ! msg }
        case None ⇒
      }
    }

    if (subscribers.nonEmpty) {
      for (key ← changed; if subscribers.contains(key); subs ← subscribers.get(key))
        notify(key, subs)
    }

    // Changed event is sent to new subscribers even though the key has not changed,
    // i.e. send current value
    if (newSubscribers.nonEmpty) {
      for ((key, subs) ← newSubscribers) {
        notify(key, subs)
        subs.foreach { subscribers.addBinding(key, _) }
      }
      newSubscribers.clear()
    }

    changed = Set.empty[String]
  }

  def receiveGossipTick(): Unit = selectRandomNode(nodes.toVector) foreach gossipTo

  def gossipTo(address: Address): Unit = {
    val to = replica(address)
    if (dataEntries.size <= maxDeltaElements) {
      val status = Status(dataEntries.map { case (key, (_, _)) ⇒ (key, getDigest(key)) }, chunk = 0, totChunks = 1)
      to ! status
    } else {
      val totChunks = dataEntries.size / maxDeltaElements
      for (_ ← 1 to math.min(totChunks, 10)) {
        if (totChunks == statusTotChunks)
          statusCount += 1
        else {
          statusCount = ThreadLocalRandom.current.nextInt(0, totChunks)
          statusTotChunks = totChunks
        }
        val chunk = (statusCount % totChunks).toInt
        val status = Status(dataEntries.collect {
          case (key, (_, _)) if math.abs(key.hashCode) % totChunks == chunk ⇒ (key, getDigest(key))
        }, chunk, totChunks)
        to ! status
      }
    }
  }

  def selectRandomNode(addresses: immutable.IndexedSeq[Address]): Option[Address] =
    if (addresses.isEmpty) None else Some(addresses(ThreadLocalRandom.current nextInt addresses.size))

  def replica(address: Address): ActorSelection =
    context.actorSelection(self.path.toStringWithAddress(address))

  def receiveStatus(otherDigests: Map[String, Digest], chunk: Int, totChunks: Int): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip status from [{}], chunk [{}] of [{}] containing [{}]", sender().path.address,
        (chunk + 1), totChunks, otherDigests.keys.mkString(", "))

    def isOtherDifferent(key: String, otherDigest: Digest): Boolean = {
      val d = getDigest(key)
      d != NotFoundDigest && d != otherDigest
    }
    val otherDifferentKeys = otherDigests.collect {
      case (key, otherDigest) if isOtherDifferent(key, otherDigest) ⇒ key
    }
    val otherKeys = otherDigests.keySet
    val myKeys =
      if (totChunks == 1) dataEntries.keySet
      else dataEntries.keysIterator.filter(_.hashCode % totChunks == chunk).toSet
    val otherMissingKeys = myKeys diff otherKeys
    val keys = (otherDifferentKeys ++ otherMissingKeys).take(maxDeltaElements)
    if (keys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip to [{}], containing [{}]", sender().path.address, keys.mkString(", "))
      val g = Gossip(keys.map(k ⇒ k → getData(k).get)(collection.breakOut), sendBack = otherDifferentKeys.nonEmpty)
      sender() ! g
    }
    val myMissingKeys = otherKeys diff myKeys
    if (myMissingKeys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip status to [{}], requesting missing [{}]", sender().path.address, myMissingKeys.mkString(", "))
      val status = Status(myMissingKeys.map(k ⇒ k → NotFoundDigest)(collection.breakOut), chunk, totChunks)
      sender() ! status
    }
  }

  def receiveGossip(updatedData: Map[String, DataEnvelope], sendBack: Boolean): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip from [{}], containing [{}]", sender().path.address, updatedData.keys.mkString(", "))
    var replyData = Map.empty[String, DataEnvelope]
    updatedData.foreach {
      case (key, envelope) ⇒
        val hadData = dataEntries.contains(key)
        write(key, envelope)
        if (sendBack) getData(key) match {
          case Some(d) ⇒
            if (hadData || d.pruning.nonEmpty)
              replyData = replyData.updated(key, d)
          case None ⇒
        }
    }
    if (sendBack && replyData.nonEmpty)
      sender() ! Gossip(replyData, sendBack = false)
  }

  def receiveSubscribe(key: KeyR, subscriber: ActorRef): Unit = {
    newSubscribers.addBinding(key.id, subscriber)
    if (!subscriptionKeys.contains(key.id))
      subscriptionKeys = subscriptionKeys.updated(key.id, key)
    context.watch(subscriber)
  }

  def receiveUnsubscribe(key: KeyR, subscriber: ActorRef): Unit = {
    subscribers.removeBinding(key.id, subscriber)
    newSubscribers.removeBinding(key.id, subscriber)
    if (!hasSubscriber(subscriber))
      context.unwatch(subscriber)
    if (!subscribers.contains(key.id) && !newSubscribers.contains(key.id))
      subscriptionKeys -= key.id
  }

  def hasSubscriber(subscriber: ActorRef): Boolean =
    (subscribers.exists { case (k, s) ⇒ s.contains(subscriber) }) ||
      (newSubscribers.exists { case (k, s) ⇒ s.contains(subscriber) })

  def receiveTerminated(ref: ActorRef): Unit = {
    val keys1 = subscribers.collect { case (k, s) if s.contains(ref) ⇒ k }
    keys1.foreach { key ⇒ subscribers.removeBinding(key, ref) }
    val keys2 = newSubscribers.collect { case (k, s) if s.contains(ref) ⇒ k }
    keys2.foreach { key ⇒ newSubscribers.removeBinding(key, ref) }

    (keys1 ++ keys2).foreach { key ⇒
      if (!subscribers.contains(key) && !newSubscribers.contains(key))
        subscriptionKeys -= key
    }
  }

  def receiveMemberUp(m: Member): Unit =
    if (matchingRole(m) && m.address != selfAddress)
      nodes += m.address

  def receiveMemberRemoved(m: Member): Unit = {
    if (m.address == selfAddress)
      context stop self
    else if (matchingRole(m)) {
      nodes -= m.address
      removedNodes = removedNodes.updated(m.uniqueAddress, allReachableClockTime)
      unreachable -= m.address
    }
  }

  def receiveUnreachable(m: Member): Unit =
    if (matchingRole(m)) unreachable += m.address

  def receiveReachable(m: Member): Unit =
    if (matchingRole(m)) unreachable -= m.address

  def receiveLeaderChanged(leaderOption: Option[Address], roleOption: Option[String]): Unit =
    if (roleOption == role) leader = leaderOption

  def receiveClockTick(): Unit = {
    val now = System.nanoTime()
    if (unreachable.isEmpty)
      allReachableClockTime += (now - previousClockTime)
    previousClockTime = now
  }

  def receiveRemovedNodePruningTick(): Unit = {
    if (isLeader && removedNodes.nonEmpty) {
      initRemovedNodePruning()
    }
    performRemovedNodePruning()
    tombstoneRemovedNodePruning()
  }

  def initRemovedNodePruning(): Unit = {
    // initiate pruning for removed nodes
    val removedSet: Set[UniqueAddress] = removedNodes.collect {
      case (r, t) if ((allReachableClockTime - t) > maxPruningDisseminationNanos) ⇒ r
    }(collection.breakOut)

    if (removedSet.nonEmpty) {
      for ((key, (envelope, _)) ← dataEntries; removed ← removedSet) {

        def init(): Unit = {
          val newEnvelope = envelope.initRemovedNodePruning(removed, selfUniqueAddress)
          log.debug("Initiated pruning of [{}] for data key [{}]", removed, key)
          setData(key, newEnvelope)
        }

        if (envelope.needPruningFrom(removed)) {
          envelope.data match {
            case dataWithRemovedNodePruning: RemovedNodePruning ⇒

              envelope.pruning.get(removed) match {
                case None ⇒ init()
                case Some(PruningState(owner, PruningInitialized(_))) if owner != selfUniqueAddress ⇒ init()
                case _ ⇒ // already in progress
              }
            case _ ⇒
          }
        }
      }
    }
  }

  def performRemovedNodePruning(): Unit = {
    // perform pruning when all seen Init
    dataEntries.foreach {
      case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
        pruning.foreach {
          case (removed, PruningState(owner, PruningInitialized(seen))) if owner == selfUniqueAddress
            && (nodes.isEmpty || nodes.forall(seen)) ⇒
            val newEnvelope = envelope.prune(removed)
            pruningPerformed = pruningPerformed.updated(removed, allReachableClockTime)
            log.debug("Perform pruning of [{}] from [{}] to [{}]", key, removed, selfUniqueAddress)
            setData(key, newEnvelope)
          case _ ⇒
        }
      case _ ⇒ // deleted, or pruning not needed
    }
  }

  def tombstoneRemovedNodePruning(): Unit = {

    def allPruningPerformed(removed: UniqueAddress): Boolean = {
      dataEntries forall {
        case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning), _)) ⇒
          pruning.get(removed) match {
            case Some(PruningState(_, PruningInitialized(_))) ⇒ false
            case _ ⇒ true
          }
        case _ ⇒ true // deleted, or pruning not needed
      }
    }

    pruningPerformed.foreach {
      case (removed, timestamp) if ((allReachableClockTime - timestamp) > maxPruningDisseminationNanos) &&
        allPruningPerformed(removed) ⇒
        log.debug("All pruning performed for [{}], tombstoned", removed)
        pruningPerformed -= removed
        removedNodes -= removed
        tombstoneNodes += removed
        dataEntries.foreach {
          case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, _), _)) ⇒
            setData(key, pruningCleanupTombstoned(removed, envelope))
          case _ ⇒ // deleted, or pruning not needed
        }
      case (removed, timestamp) ⇒ // not ready
    }
  }

  def pruningCleanupTombstoned(envelope: DataEnvelope): DataEnvelope =
    tombstoneNodes.foldLeft(envelope)((c, removed) ⇒ pruningCleanupTombstoned(removed, c))

  def pruningCleanupTombstoned(removed: UniqueAddress, envelope: DataEnvelope): DataEnvelope = {
    val pruningCleanuped = pruningCleanupTombstoned(removed, envelope.data)
    if ((pruningCleanuped ne envelope.data) || envelope.pruning.contains(removed))
      envelope.copy(data = pruningCleanuped, pruning = envelope.pruning - removed)
    else
      envelope
  }

  def pruningCleanupTombstoned(data: ReplicatedData): ReplicatedData =
    if (tombstoneNodes.isEmpty) data
    else tombstoneNodes.foldLeft(data)((c, removed) ⇒ pruningCleanupTombstoned(removed, c))

  def pruningCleanupTombstoned(removed: UniqueAddress, data: ReplicatedData): ReplicatedData =
    data match {
      case dataWithRemovedNodePruning: RemovedNodePruning ⇒
        if (dataWithRemovedNodePruning.needPruningFrom(removed)) dataWithRemovedNodePruning.pruningCleanup(removed) else data
      case _ ⇒ data
    }

  def receiveGetReplicaCount(): Unit = {
    // selfAddress is not included in the set
    sender() ! ReplicaCount(nodes.size + 1)
  }

}

/**
 * INTERNAL API
 */
private[akka] object ReadWriteAggregator {
  case object SendToSecondary
  val MaxSecondaryNodes = 10
}

/**
 * INTERNAL API
 */
private[akka] abstract class ReadWriteAggregator extends Actor {
  import ReadWriteAggregator._

  def timeout: FiniteDuration
  def nodes: Set[Address]

  import context.dispatcher
  var sendToSecondarySchedule = context.system.scheduler.scheduleOnce(timeout / 5, self, SendToSecondary)
  var timeoutSchedule = context.system.scheduler.scheduleOnce(timeout, self, ReceiveTimeout)

  var remaining = nodes

  def doneWhenRemainingSize: Int

  lazy val (primaryNodes, secondaryNodes) = {
    val primarySize = nodes.size - doneWhenRemainingSize
    if (primarySize >= nodes.size)
      (nodes, Set.empty[Address])
    else {
      val (p, s) = scala.util.Random.shuffle(nodes.toVector).splitAt(primarySize)
      (p, s.take(MaxSecondaryNodes))
    }
  }

  override def postStop(): Unit = {
    sendToSecondarySchedule.cancel()
    timeoutSchedule.cancel()
  }

  def replica(address: Address): ActorSelection =
    context.actorSelection(context.parent.path.toStringWithAddress(address))

}

/**
 * INTERNAL API
 */
private[akka] object WriteAggregator {
  def props(
    key:         KeyR,
    envelope:    Replicator.Internal.DataEnvelope,
    consistency: Replicator.WriteConsistency,
    req:         Option[Any],
    nodes:       Set[Address],
    replyTo:     ActorRef): Props =
    Props(new WriteAggregator(key, envelope, consistency, req, nodes, replyTo))
      .withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
private[akka] class WriteAggregator(
  key:                KeyR,
  envelope:           Replicator.Internal.DataEnvelope,
  consistency:        Replicator.WriteConsistency,
  req:                Option[Any],
  override val nodes: Set[Address],
  replyTo:            ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._
  import ReadWriteAggregator._

  override def timeout: FiniteDuration = consistency.timeout

  override val doneWhenRemainingSize = consistency match {
    case WriteTo(n, _) ⇒ nodes.size - (n - 1)
    case _: WriteAll   ⇒ 0
    case _: WriteMajority ⇒
      val N = nodes.size + 1
      val w = N / 2 + 1 // write to at least (N/2+1) nodes
      N - w
    case WriteLocal ⇒
      throw new IllegalArgumentException("ReadLocal not supported by WriteAggregator")
  }

  val writeMsg = Write(key.id, envelope)

  override def preStart(): Unit = {
    primaryNodes.foreach { replica(_) ! writeMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case WriteAck ⇒
      remaining -= senderAddress()
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case SendToSecondary ⇒
      secondaryNodes.foreach { replica(_) ! writeMsg }
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def senderAddress(): Address = sender().path.address

  def reply(ok: Boolean): Unit = {
    if (ok && envelope.data == DeletedData)
      replyTo.tell(DeleteSuccess(key), context.parent)
    else if (ok)
      replyTo.tell(UpdateSuccess(key, req), context.parent)
    else if (envelope.data == DeletedData)
      replyTo.tell(ReplicationDeleteFailure(key), context.parent)
    else
      replyTo.tell(UpdateTimeout(key, req), context.parent)
    context.stop(self)
  }
}

/**
 * INTERNAL API
 */
private[akka] object ReadAggregator {
  def props(
    key:         KeyR,
    consistency: Replicator.ReadConsistency,
    req:         Option[Any],
    nodes:       Set[Address],
    localValue:  Option[Replicator.Internal.DataEnvelope],
    replyTo:     ActorRef): Props =
    Props(new ReadAggregator(key, consistency, req, nodes, localValue, replyTo))
      .withDeploy(Deploy.local)

}

/**
 * INTERNAL API
 */
private[akka] class ReadAggregator(
  key:                KeyR,
  consistency:        Replicator.ReadConsistency,
  req:                Option[Any],
  override val nodes: Set[Address],
  localValue:         Option[Replicator.Internal.DataEnvelope],
  replyTo:            ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._
  import ReadWriteAggregator._

  override def timeout: FiniteDuration = consistency.timeout

  var result = localValue
  override val doneWhenRemainingSize = consistency match {
    case ReadFrom(n, _) ⇒ nodes.size - (n - 1)
    case _: ReadAll     ⇒ 0
    case _: ReadMajority ⇒
      val N = nodes.size + 1
      val r = N / 2 + 1 // read from at least (N/2+1) nodes
      N - r
    case ReadLocal ⇒
      throw new IllegalArgumentException("ReadLocal not supported by ReadAggregator")
  }

  val readMsg = Read(key.id)

  override def preStart(): Unit = {
    primaryNodes.foreach { replica(_) ! readMsg }

    if (remaining.size == doneWhenRemainingSize)
      reply(ok = true)
    else if (doneWhenRemainingSize < 0 || remaining.size < doneWhenRemainingSize)
      reply(ok = false)
  }

  def receive = {
    case ReadResult(envelope) ⇒
      result = (result, envelope) match {
        case (Some(a), Some(b))  ⇒ Some(a.merge(b))
        case (r @ Some(_), None) ⇒ r
        case (None, r @ Some(_)) ⇒ r
        case (None, None)        ⇒ None
      }
      remaining -= sender().path.address
      if (remaining.size == doneWhenRemainingSize)
        reply(ok = true)
    case SendToSecondary ⇒
      secondaryNodes.foreach { replica(_) ! readMsg }
    case ReceiveTimeout ⇒ reply(ok = false)
  }

  def reply(ok: Boolean): Unit =
    (ok, result) match {
      case (true, Some(envelope)) ⇒
        context.parent ! ReadRepair(key.id, envelope)
        // read-repair happens before GetSuccess
        context.become(waitReadRepairAck(envelope))
      case (true, None) ⇒
        replyTo.tell(NotFound(key, req), context.parent)
        context.stop(self)
      case (false, _) ⇒
        replyTo.tell(GetFailure(key, req), context.parent)
        context.stop(self)
    }

  def waitReadRepairAck(envelope: Replicator.Internal.DataEnvelope): Receive = {
    case ReadRepairAck ⇒
      val replyMsg =
        if (envelope.data == DeletedData) DataDeleted(key)
        else GetSuccess(key, req)(envelope.data)
      replyTo.tell(replyMsg, context.parent)
      context.stop(self)
    case _: ReadResult ⇒
      //collect late replies
      remaining -= sender().path.address
    case SendToSecondary ⇒
    case ReceiveTimeout  ⇒
  }
}

