/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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
import akka.cluster.ddata.DurableStore._
import akka.actor.ExtendedActorSystem
import akka.actor.SupervisorStrategy
import akka.actor.OneForOneStrategy
import akka.actor.ActorInitializationException
import java.util.concurrent.TimeUnit
import akka.util.Helpers.toRootLowerCase
import akka.actor.Cancellable
import scala.util.control.NonFatal
import akka.cluster.ddata.Key.KeyId
import akka.annotation.InternalApi
import scala.collection.immutable.TreeSet
import akka.cluster.MemberStatus
import scala.annotation.varargs

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

    val pruningInterval = toRootLowerCase(config.getString("pruning-interval")) match {
      case "off" | "false" ⇒ Duration.Zero
      case _               ⇒ config.getDuration("pruning-interval", MILLISECONDS).millis
    }

    import scala.collection.JavaConverters._
    new ReplicatorSettings(
      role = roleOption(config.getString("role")),
      gossipInterval = config.getDuration("gossip-interval", MILLISECONDS).millis,
      notifySubscribersInterval = config.getDuration("notify-subscribers-interval", MILLISECONDS).millis,
      maxDeltaElements = config.getInt("max-delta-elements"),
      dispatcher = dispatcher,
      pruningInterval = pruningInterval,
      maxPruningDissemination = config.getDuration("max-pruning-dissemination", MILLISECONDS).millis,
      durableStoreProps = Left((config.getString("durable.store-actor-class"), config.getConfig("durable"))),
      durableKeys = config.getStringList("durable.keys").asScala.toSet,
      pruningMarkerTimeToLive = config.getDuration("pruning-marker-time-to-live", MILLISECONDS).millis,
      durablePruningMarkerTimeToLive = config.getDuration("durable.pruning-marker-time-to-live", MILLISECONDS).millis,
      deltaCrdtEnabled = config.getBoolean("delta-crdt.enabled"),
      maxDeltaSize = config.getInt("delta-crdt.max-delta-size"))
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)
}

/**
 * @param roles Replicas are running on members tagged with these roles.
 *   The member must have all given roles. All members are used if empty.
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
 * @param durableStoreProps Props for the durable store actor,
 *        the `Left` alternative is a tuple of fully qualified actor class name and
 *        the config constructor parameter of that class,
 *        the `Right` alternative is the `Props` of the actor.
 * @param durableKeys Keys that are durable. Prefix matching is supported by using
 *        `*` at the end of a key. All entries can be made durable by including "*"
 *        in the `Set`.
 */
final class ReplicatorSettings(
  val roles:                          Set[String],
  val gossipInterval:                 FiniteDuration,
  val notifySubscribersInterval:      FiniteDuration,
  val maxDeltaElements:               Int,
  val dispatcher:                     String,
  val pruningInterval:                FiniteDuration,
  val maxPruningDissemination:        FiniteDuration,
  val durableStoreProps:              Either[(String, Config), Props],
  val durableKeys:                    Set[KeyId],
  val pruningMarkerTimeToLive:        FiniteDuration,
  val durablePruningMarkerTimeToLive: FiniteDuration,
  val deltaCrdtEnabled:               Boolean,
  val maxDeltaSize:                   Int) {

  // for backwards compatibility
  def this(
    role:                           Option[String],
    gossipInterval:                 FiniteDuration,
    notifySubscribersInterval:      FiniteDuration,
    maxDeltaElements:               Int,
    dispatcher:                     String,
    pruningInterval:                FiniteDuration,
    maxPruningDissemination:        FiniteDuration,
    durableStoreProps:              Either[(String, Config), Props],
    durableKeys:                    Set[KeyId],
    pruningMarkerTimeToLive:        FiniteDuration,
    durablePruningMarkerTimeToLive: FiniteDuration,
    deltaCrdtEnabled:               Boolean,
    maxDeltaSize:                   Int) =
    this(role.toSet, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher, pruningInterval,
      maxPruningDissemination, durableStoreProps, durableKeys, pruningMarkerTimeToLive, durablePruningMarkerTimeToLive,
      deltaCrdtEnabled, maxDeltaSize)

  // For backwards compatibility
  def this(role: Option[String], gossipInterval: FiniteDuration, notifySubscribersInterval: FiniteDuration,
           maxDeltaElements: Int, dispatcher: String, pruningInterval: FiniteDuration, maxPruningDissemination: FiniteDuration) =
    this(roles = role.toSet, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher, pruningInterval,
      maxPruningDissemination, Right(Props.empty), Set.empty, 6.hours, 10.days, true, 200)

  // For backwards compatibility
  def this(role: Option[String], gossipInterval: FiniteDuration, notifySubscribersInterval: FiniteDuration,
           maxDeltaElements: Int, dispatcher: String, pruningInterval: FiniteDuration, maxPruningDissemination: FiniteDuration,
           durableStoreProps: Either[(String, Config), Props], durableKeys: Set[String]) =
    this(role, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher, pruningInterval,
      maxPruningDissemination, durableStoreProps, durableKeys, 6.hours, 10.days, true, 200)

  // For backwards compatibility
  def this(role: Option[String], gossipInterval: FiniteDuration, notifySubscribersInterval: FiniteDuration,
           maxDeltaElements: Int, dispatcher: String, pruningInterval: FiniteDuration, maxPruningDissemination: FiniteDuration,
           durableStoreProps: Either[(String, Config), Props], durableKeys: Set[String],
           pruningMarkerTimeToLive: FiniteDuration, durablePruningMarkerTimeToLive: FiniteDuration,
           deltaCrdtEnabled: Boolean) =
    this(role, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher, pruningInterval,
      maxPruningDissemination, durableStoreProps, durableKeys, pruningMarkerTimeToLive, durablePruningMarkerTimeToLive,
      deltaCrdtEnabled, 200)

  def withRole(role: String): ReplicatorSettings = copy(roles = ReplicatorSettings.roleOption(role).toSet)

  def withRole(role: Option[String]): ReplicatorSettings = copy(roles = role.toSet)

  @varargs
  def withRoles(roles: String*): ReplicatorSettings = copy(roles = roles.toSet)

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def withRoles(roles: Set[String]): ReplicatorSettings = copy(roles = roles)

  // for backwards compatibility
  def role: Option[String] = roles.headOption

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

  def withPruningMarkerTimeToLive(
    pruningMarkerTimeToLive:        FiniteDuration,
    durablePruningMarkerTimeToLive: FiniteDuration): ReplicatorSettings =
    copy(
      pruningMarkerTimeToLive = pruningMarkerTimeToLive,
      durablePruningMarkerTimeToLive = durablePruningMarkerTimeToLive)

  def withDurableStoreProps(durableStoreProps: Props): ReplicatorSettings =
    copy(durableStoreProps = Right(durableStoreProps))

  /**
   * Scala API
   */
  def withDurableKeys(durableKeys: Set[KeyId]): ReplicatorSettings =
    copy(durableKeys = durableKeys)

  /**
   * Java API
   */
  def withDurableKeys(durableKeys: java.util.Set[String]): ReplicatorSettings = {
    import scala.collection.JavaConverters._
    withDurableKeys(durableKeys.asScala.toSet)
  }

  def withDeltaCrdtEnabled(deltaCrdtEnabled: Boolean): ReplicatorSettings =
    copy(deltaCrdtEnabled = deltaCrdtEnabled)

  def withMaxDeltaSize(maxDeltaSize: Int): ReplicatorSettings =
    copy(maxDeltaSize = maxDeltaSize)

  private def copy(
    roles:                          Set[String]                     = roles,
    gossipInterval:                 FiniteDuration                  = gossipInterval,
    notifySubscribersInterval:      FiniteDuration                  = notifySubscribersInterval,
    maxDeltaElements:               Int                             = maxDeltaElements,
    dispatcher:                     String                          = dispatcher,
    pruningInterval:                FiniteDuration                  = pruningInterval,
    maxPruningDissemination:        FiniteDuration                  = maxPruningDissemination,
    durableStoreProps:              Either[(String, Config), Props] = durableStoreProps,
    durableKeys:                    Set[KeyId]                      = durableKeys,
    pruningMarkerTimeToLive:        FiniteDuration                  = pruningMarkerTimeToLive,
    durablePruningMarkerTimeToLive: FiniteDuration                  = durablePruningMarkerTimeToLive,
    deltaCrdtEnabled:               Boolean                         = deltaCrdtEnabled,
    maxDeltaSize:                   Int                             = maxDeltaSize): ReplicatorSettings =
    new ReplicatorSettings(roles, gossipInterval, notifySubscribersInterval, maxDeltaElements, dispatcher,
      pruningInterval, maxPruningDissemination, durableStoreProps, durableKeys,
      pruningMarkerTimeToLive, durablePruningMarkerTimeToLive, deltaCrdtEnabled, maxDeltaSize)
}

object Replicator {

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Replicator]] actor.
   */
  def props(settings: ReplicatorSettings): Props = {
    require(
      settings.durableKeys.isEmpty || (settings.durableStoreProps != Right(Props.empty)),
      "durableStoreProps must be defined when durableKeys are defined")
    Props(new Replicator(settings)).withDeploy(Deploy.local).withDispatcher(settings.dispatcher)
  }

  val DefaultMajorityMinCap: Int = 0

  sealed trait ReadConsistency {
    def timeout: FiniteDuration
  }
  case object ReadLocal extends ReadConsistency {
    override def timeout: FiniteDuration = Duration.Zero
  }
  final case class ReadFrom(n: Int, timeout: FiniteDuration) extends ReadConsistency {
    require(n >= 2, "ReadFrom n must be >= 2, use ReadLocal for n=1")
  }
  final case class ReadMajority(timeout: FiniteDuration, minCap: Int = DefaultMajorityMinCap) extends ReadConsistency {
    def this(timeout: FiniteDuration) = this(timeout, DefaultMajorityMinCap)
  }
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
  final case class WriteMajority(timeout: FiniteDuration, minCap: Int = DefaultMajorityMinCap) extends WriteConsistency {
    def this(timeout: FiniteDuration) = this(timeout, DefaultMajorityMinCap)
  }
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
  @InternalApi private[akka] case object GetKeyIds

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] final case class GetKeyIdsResult(keyIds: Set[KeyId]) {
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
   * If the key is deleted the subscriber is notified with a [[Deleted]]
   * message.
   */
  final case class Subscribe[A <: ReplicatedData](key: Key[A], subscriber: ActorRef) extends ReplicatorMessage
  /**
   * Unregister a subscriber.
   *
   * @see [[Replicator.Subscribe]]
   */
  final case class Unsubscribe[A <: ReplicatedData](key: Key[A], subscriber: ActorRef) extends ReplicatorMessage
  /**
   * The data value is retrieved with [[#get]] using the typed key.
   *
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

  final case class Deleted[A <: ReplicatedData](key: Key[A]) extends NoSerializationVerificationNeeded {
    override def toString: String = s"Deleted [$key]"
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
   * The local store or direct replication of the [[Update]] could not be fulfill according to
   * the given [[WriteConsistency consistency level]] due to durable store errors. This is
   * only used for entries that have been configured to be durable.
   *
   * The `Update` was still performed in memory locally and possibly replicated to some nodes,
   * but it might not have been written to durable storage.
   * It will eventually be disseminated to other replicas, unless the local replica
   * crashes before it has been able to communicate with other replicas.
   */
  final case class StoreFailure[A <: ReplicatedData](key: Key[A], request: Option[Any])
    extends UpdateFailure[A] with DeleteResponse[A] {

    /** Java API */
    override def getRequest: Optional[Any] = Optional.ofNullable(request.orNull)
  }

  /**
   * Send this message to the local `Replicator` to delete a data value for the
   * given `key`. The `Replicator` will reply with one of the [[DeleteResponse]] messages.
   *
   * The optional `request` context is included in the reply messages. This is a convenient
   * way to pass contextual information (e.g. original sender) without having to use `ask`
   * or maintain local correlation data structures.
   */
  final case class Delete[A <: ReplicatedData](key: Key[A], consistency: WriteConsistency, request: Option[Any] = None)
    extends Command[A] with NoSerializationVerificationNeeded {

    def this(key: Key[A], consistency: WriteConsistency) = this(key, consistency, None)

    def this(key: Key[A], consistency: WriteConsistency, request: Optional[Any]) =
      this(key, consistency, Option(request.orElse(null)))
  }

  sealed trait DeleteResponse[A <: ReplicatedData] extends NoSerializationVerificationNeeded {
    def key: Key[A]
    def request: Option[Any]

    /** Java API*/
    def getRequest: Optional[Any] = Optional.ofNullable(request.orNull)
  }
  final case class DeleteSuccess[A <: ReplicatedData](key: Key[A], request: Option[Any]) extends DeleteResponse[A]
  final case class ReplicationDeleteFailure[A <: ReplicatedData](key: Key[A], request: Option[Any]) extends DeleteResponse[A]
  final case class DataDeleted[A <: ReplicatedData](key: Key[A], request: Option[Any])
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
  @InternalApi private[akka] object Internal {

    case object GossipTick
    case object DeltaPropagationTick
    case object RemovedNodePruningTick
    case object ClockTick
    final case class Write(key: KeyId, envelope: DataEnvelope) extends ReplicatorMessage
    case object WriteAck extends ReplicatorMessage with DeadLetterSuppression
    case object WriteNack extends ReplicatorMessage with DeadLetterSuppression
    final case class Read(key: KeyId) extends ReplicatorMessage
    final case class ReadResult(envelope: Option[DataEnvelope]) extends ReplicatorMessage with DeadLetterSuppression
    final case class ReadRepair(key: KeyId, envelope: DataEnvelope)
    case object ReadRepairAck
    // for testing purposes
    final case class TestFullStateGossip(enabled: Boolean)

    // Gossip Status message contains SHA-1 digests of the data to determine when
    // to send the full data
    type Digest = ByteString
    val DeletedDigest: Digest = ByteString.empty
    val LazyDigest: Digest = ByteString(0)
    val NotFoundDigest: Digest = ByteString(-1)

    /**
     * The `DataEnvelope` wraps a data entry and carries state of the pruning process for the entry.
     */
    final case class DataEnvelope(
      data:          ReplicatedData,
      pruning:       Map[UniqueAddress, PruningState] = Map.empty,
      deltaVersions: VersionVector                    = VersionVector.empty)
      extends ReplicatorMessage {

      import PruningState._

      def withoutDeltaVersions: DataEnvelope =
        if (deltaVersions.isEmpty) this
        else copy(deltaVersions = VersionVector.empty)

      /**
       * We only use the deltaVersions to track versions per node, not for ordering comparisons,
       * so we can just remove the entry for the removed node.
       */
      private def cleanedDeltaVersions(from: UniqueAddress): VersionVector =
        deltaVersions.pruningCleanup(from)

      def needPruningFrom(removedNode: UniqueAddress): Boolean =
        data match {
          case r: RemovedNodePruning ⇒ r.needPruningFrom(removedNode)
          case _                     ⇒ false
        }

      def initRemovedNodePruning(removed: UniqueAddress, owner: UniqueAddress): DataEnvelope = {
        copy(
          pruning = pruning.updated(removed, PruningInitialized(owner, Set.empty)),
          deltaVersions = cleanedDeltaVersions(removed))
      }

      def prune(from: UniqueAddress, pruningPerformed: PruningPerformed): DataEnvelope = {
        data match {
          case dataWithRemovedNodePruning: RemovedNodePruning ⇒
            require(pruning.contains(from))
            pruning(from) match {
              case PruningInitialized(owner, _) ⇒
                val prunedData = dataWithRemovedNodePruning.prune(from, owner)
                copy(data = prunedData, pruning = pruning.updated(from, pruningPerformed),
                  deltaVersions = cleanedDeltaVersions(from))
              case _ ⇒
                this
            }

          case _ ⇒ this
        }
      }

      def merge(other: DataEnvelope): DataEnvelope =
        if (other.data == DeletedData) DeletedEnvelope
        else {
          val mergedPruning =
            pruning.foldLeft(other.pruning) {
              case (acc, (key, thisValue)) ⇒
                acc.get(key) match {
                  case None ⇒
                    acc.updated(key, thisValue)
                  case Some(thatValue) ⇒
                    acc.updated(key, thisValue merge thatValue)
                }
            }
          val filteredMergedPruning = {
            if (mergedPruning.isEmpty) mergedPruning
            else {
              val currentTime = System.currentTimeMillis()
              mergedPruning.filter {
                case (_, p: PruningPerformed) ⇒ !p.isObsolete(currentTime)
                case _                        ⇒ true
              }
            }
          }

          // cleanup and merge deltaVersions
          val removedNodes = filteredMergedPruning.keys
          val cleanedDV = removedNodes.foldLeft(deltaVersions) { (acc, node) ⇒ acc.pruningCleanup(node) }
          val cleanedOtherDV = removedNodes.foldLeft(other.deltaVersions) { (acc, node) ⇒ acc.pruningCleanup(node) }
          val mergedDeltaVersions = cleanedDV.merge(cleanedOtherDV)

          // cleanup both sides before merging, `merge(otherData: ReplicatedData)` will cleanup other.data
          copy(
            data = cleaned(data, filteredMergedPruning),
            deltaVersions = mergedDeltaVersions,
            pruning = filteredMergedPruning).merge(other.data)
        }

      def merge(otherData: ReplicatedData): DataEnvelope = {
        if (otherData == DeletedData) DeletedEnvelope
        else {
          val mergedData =
            cleaned(otherData, pruning) match {
              case d: ReplicatedDelta ⇒ data match {
                case drd: DeltaReplicatedData ⇒ drd.mergeDelta(d.asInstanceOf[drd.D])
                case _                        ⇒ throw new IllegalArgumentException("Expected DeltaReplicatedData")
              }
              case c ⇒ data.merge(c.asInstanceOf[data.T])
            }
          if (data.getClass != mergedData.getClass)
            throw new IllegalArgumentException(
              s"Wrong type, existing type [${data.getClass.getName}], got [${mergedData.getClass.getName}]")
          copy(data = mergedData)
        }
      }

      private def cleaned(c: ReplicatedData, p: Map[UniqueAddress, PruningState]): ReplicatedData = p.foldLeft(c) {
        case (c: RemovedNodePruning, (removed, _: PruningPerformed)) ⇒
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

    final case class Status(digests: Map[KeyId, Digest], chunk: Int, totChunks: Int) extends ReplicatorMessage {
      override def toString: String =
        (digests.map {
          case (key, bytes) ⇒ key + " -> " + bytes.map(byte ⇒ f"$byte%02x").mkString("")
        }).mkString("Status(", ", ", ")")
    }
    final case class Gossip(updatedData: Map[KeyId, DataEnvelope], sendBack: Boolean) extends ReplicatorMessage

    final case class Delta(dataEnvelope: DataEnvelope, fromSeqNr: Long, toSeqNr: Long)
    final case class DeltaPropagation(fromNode: UniqueAddress, reply: Boolean, deltas: Map[KeyId, Delta]) extends ReplicatorMessage
    object DeltaPropagation {
      /**
       * When a DeltaReplicatedData returns `None` from `delta` it must still be
       * treated as a delta that increase the version counter in `DeltaPropagationSelector`.
       * Otherwise a later delta might be applied before the full state gossip is received
       * and thereby violating `RequiresCausalDeliveryOfDeltas`.
       *
       * This is used as a placeholder for such `None` delta. It's filtered out
       * in `createDeltaPropagation`, i.e. never sent to the other replicas.
       */
      val NoDeltaPlaceholder: ReplicatedDelta =
        new DeltaReplicatedData with RequiresCausalDeliveryOfDeltas with ReplicatedDelta {
          type T = ReplicatedData
          type D = ReplicatedDelta
          override def merge(other: ReplicatedData): ReplicatedData = this
          override def mergeDelta(other: ReplicatedDelta): ReplicatedDelta = this
          override def zero: DeltaReplicatedData = this
          override def delta: Option[ReplicatedDelta] = None
          override def resetDelta: ReplicatedData = this
        }
    }
    case object DeltaNack extends ReplicatorMessage with DeadLetterSuppression

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
 * You can use your own custom [[ReplicatedData]] or [[DeltaReplicatedData]] types,
 * and several types are provided by this package, such as:
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
 * can be used with the [[DistributedData]] extension but it can also be started as an ordinary
 * actor using the `Replicator.props`. If it is started as an ordinary actor it is important
 * that it is given the same name, started on same path, on all nodes.
 *
 * <a href="paper http://arxiv.org/abs/1603.01529">Delta State Replicated Data Types</a>
 * is supported. delta-CRDT is a way to reduce the need for sending the full state
 * for updates. For example adding element 'c' and 'd' to set {'a', 'b'} would
 * result in sending the delta {'c', 'd'} and merge that with the state on the
 * receiving side, resulting in set {'a', 'b', 'c', 'd'}.
 *
 * The protocol for replicating the deltas supports causal consistency if the data type
 * is marked with [[RequiresCausalDeliveryOfDeltas]]. Otherwise it is only eventually
 * consistent. Without causal consistency it means that if elements 'c' and 'd' are
 * added in two separate `Update` operations these deltas may occasionally be propagated
 * to nodes in different order than the causal order of the updates. For this example it
 * can result in that set {'a', 'b', 'd'} can be seen before element 'c' is seen. Eventually
 * it will be {'a', 'b', 'c', 'd'}.
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
 * Subscribers will receive [[Replicator.Deleted]].
 *
 * In the `Delete` message you can pass an optional request context in the same way as for the
 * `Update` message, described above. For example the original sender can be passed and replied
 * to after receiving and transforming `DeleteSuccess`.
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
 * replica is unreachable, but it's still recommended to configure this with certain margin.
 * It should be in the magnitude of minutes.</li>
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
  import Replicator.Internal.DeltaPropagation.NoDeltaPlaceholder
  import PruningState._
  import settings._

  val cluster = Cluster(context.system)
  val selfAddress = cluster.selfAddress
  val selfUniqueAddress = cluster.selfUniqueAddress

  require(!cluster.isTerminated, "Cluster node must not be terminated")
  require(
    roles.subsetOf(cluster.selfRoles),
    s"This cluster member [${selfAddress}] doesn't have all the roles [${roles.mkString(", ")}]")

  //Start periodic gossip to random nodes in cluster
  import context.dispatcher
  val gossipTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, GossipTick)
  val notifyTask = context.system.scheduler.schedule(notifySubscribersInterval, notifySubscribersInterval, self, FlushChanges)
  val pruningTask =
    if (pruningInterval >= Duration.Zero)
      Some(context.system.scheduler.schedule(pruningInterval, pruningInterval, self, RemovedNodePruningTick))
    else None
  val clockTask = context.system.scheduler.schedule(gossipInterval, gossipInterval, self, ClockTick)

  val serializer = SerializationExtension(context.system).serializerFor(classOf[DataEnvelope])
  val maxPruningDisseminationNanos = maxPruningDissemination.toNanos

  val hasDurableKeys = settings.durableKeys.nonEmpty
  val durable = settings.durableKeys.filterNot(_.endsWith("*"))
  val durableWildcards = settings.durableKeys.collect { case k if k.endsWith("*") ⇒ k.dropRight(1) }
  val durableStore: ActorRef =
    if (hasDurableKeys) {
      val props = settings.durableStoreProps match {
        case Right(p) ⇒ p
        case Left((s, c)) ⇒
          val clazz = context.system.asInstanceOf[ExtendedActorSystem].dynamicAccess.getClassFor[Actor](s).get
          Props(clazz, c).withDispatcher(c.getString("use-dispatcher"))
      }
      context.watch(context.actorOf(props.withDeploy(Deploy.local), "durableStore"))
    } else
      context.system.deadLetters // not used

  val deltaPropagationSelector = new DeltaPropagationSelector {
    override val gossipIntervalDivisor = 5
    override def allNodes: Vector[Address] = {
      // TODO optimize, by maintaining a sorted instance variable instead
      nodes.union(weaklyUpNodes).diff(unreachable).toVector.sorted
    }

    override def maxDeltaSize: Int = settings.maxDeltaSize

    override def createDeltaPropagation(deltas: Map[KeyId, (ReplicatedData, Long, Long)]): DeltaPropagation = {
      // Important to include the pruning state in the deltas. For example if the delta is based
      // on an entry that has been pruned but that has not yet been performed on the target node.
      DeltaPropagation(selfUniqueAddress, reply = false, deltas.collect {
        case (key, (d, fromSeqNr, toSeqNr)) if d != NoDeltaPlaceholder ⇒
          getData(key) match {
            case Some(envelope) ⇒ key → Delta(envelope.copy(data = d), fromSeqNr, toSeqNr)
            case None           ⇒ key → Delta(DataEnvelope(d), fromSeqNr, toSeqNr)
          }
      }(collection.breakOut))
    }
  }
  val deltaPropagationTask: Option[Cancellable] =
    if (deltaCrdtEnabled) {
      // Derive the deltaPropagationInterval from the gossipInterval.
      // Normally the delta is propagated to all nodes within the gossip tick, so that
      // full state gossip is not needed.
      val deltaPropagationInterval = (gossipInterval / deltaPropagationSelector.gossipIntervalDivisor).max(200.millis)
      Some(context.system.scheduler.schedule(deltaPropagationInterval, deltaPropagationInterval,
        self, DeltaPropagationTick))
    } else None

  // cluster nodes, doesn't contain selfAddress
  var nodes: Set[Address] = Set.empty

  // cluster weaklyUp nodes, doesn't contain selfAddress
  var weaklyUpNodes: Set[Address] = Set.empty

  var removedNodes: Map[UniqueAddress, Long] = Map.empty
  // all nodes sorted with the leader first
  var leader: TreeSet[Member] = TreeSet.empty(Member.leaderStatusOrdering)
  def isLeader: Boolean =
    leader.nonEmpty && leader.head.address == selfAddress && leader.head.status == MemberStatus.Up

  // for pruning timeouts are based on clock that is only increased when all nodes are reachable
  var previousClockTime = System.nanoTime()
  var allReachableClockTime = 0L
  var unreachable = Set.empty[Address]

  // the actual data
  var dataEntries = Map.empty[KeyId, (DataEnvelope, Digest)]
  // keys that have changed, Changed event published to subscribers on FlushChanges
  var changed = Set.empty[KeyId]

  // for splitting up gossip in chunks
  var statusCount = 0L
  var statusTotChunks = 0
  // possibility to disable Gossip for testing purpose
  var fullStateGossipEnabled = true

  val subscribers = new mutable.HashMap[KeyId, mutable.Set[ActorRef]] with mutable.MultiMap[KeyId, ActorRef]
  val newSubscribers = new mutable.HashMap[KeyId, mutable.Set[ActorRef]] with mutable.MultiMap[KeyId, ActorRef]
  var subscriptionKeys = Map.empty[KeyId, KeyR]

  // To be able to do efficient stashing we use this field instead of sender().
  // Using internal buffer instead of Stash to avoid the overhead of the Stash mailbox.
  // sender() and forward must not be used from normalReceive.
  // It's set to sender() from aroundReceive, but is also used when unstashing
  // messages after loading durable data.
  var replyTo: ActorRef = null

  override protected[akka] def aroundReceive(rcv: Actor.Receive, msg: Any): Unit = {
    replyTo = sender()
    try {
      super.aroundReceive(rcv, msg)
    } finally {
      replyTo = null
    }
  }

  override def preStart(): Unit = {
    if (hasDurableKeys)
      durableStore ! LoadAll
    // not using LeaderChanged/RoleLeaderChanged because here we need one node independent of team
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[ReachabilityEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
    gossipTask.cancel()
    deltaPropagationTask.foreach(_.cancel())
    notifyTask.cancel()
    pruningTask.foreach(_.cancel())
    clockTask.cancel()
  }

  def matchingRole(m: Member): Boolean = roles.subsetOf(m.roles)

  override val supervisorStrategy = {
    def fromDurableStore: Boolean = sender() == durableStore && sender() != context.system.deadLetters
    OneForOneStrategy()(
      ({
        case e @ (_: DurableStore.LoadFailed | _: ActorInitializationException) if fromDurableStore ⇒
          log.error(e, "Stopping distributed-data Replicator due to load or startup failure in durable store, caused by: {}", if (e.getCause eq null) "" else e.getCause.getMessage)
          context.stop(self)
          SupervisorStrategy.Stop
      }: SupervisorStrategy.Decider).orElse(SupervisorStrategy.defaultDecider))
  }

  def receive =
    if (hasDurableKeys) load
    else normalReceive

  val load: Receive = {
    val startTime = System.nanoTime()
    var count = 0
    // Using internal buffer instead of Stash to avoid the overhead of the Stash mailbox.
    var stash = Vector.empty[(Any, ActorRef)]

    def unstashAll(): Unit = {
      val originalReplyTo = replyTo
      stash.foreach {
        case (msg, snd) ⇒
          replyTo = snd
          normalReceive.applyOrElse(msg, unhandled)
      }
      stash = Vector.empty
      replyTo = originalReplyTo
    }

    {
      case LoadData(data) ⇒
        count += data.size
        data.foreach {
          case (key, d) ⇒
            write(key, d.dataEnvelope) match {
              case Some(newEnvelope) ⇒
                if (newEnvelope ne d.dataEnvelope)
                  durableStore ! Store(key, new DurableDataEnvelope(newEnvelope), None)
              case None ⇒
            }
        }
      case LoadAllCompleted ⇒
        log.debug(
          "Loading {} entries from durable store took {} ms, stashed {}",
          count, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime), stash.size)
        context.become(normalReceive)
        unstashAll()
        self ! FlushChanges

      case GetReplicaCount ⇒
        // 0 until durable data has been loaded, used by test
        replyTo ! ReplicaCount(0)

      case RemovedNodePruningTick | FlushChanges | GossipTick ⇒
      // ignore scheduled ticks when loading durable data
      case TestFullStateGossip(enabled) ⇒
        fullStateGossipEnabled = enabled
      case m @ (_: Read | _: Write | _: Status | _: Gossip) ⇒
        // ignore gossip and replication when loading durable data
        log.debug("ignoring message [{}] when loading durable data", m.getClass.getName)
      case msg: ClusterDomainEvent ⇒ normalReceive.applyOrElse(msg, unhandled)
      case msg ⇒
        stash :+= (msg → replyTo)
    }
  }

  // MUST use replyTo instead of sender() and forward from normalReceive, because of the stash in load
  val normalReceive: Receive = {
    case Get(key, consistency, req)             ⇒ receiveGet(key, consistency, req)
    case u @ Update(key, writeC, req)           ⇒ receiveUpdate(key, u.modify, writeC, req)
    case Read(key)                              ⇒ receiveRead(key)
    case Write(key, envelope)                   ⇒ receiveWrite(key, envelope)
    case ReadRepair(key, envelope)              ⇒ receiveReadRepair(key, envelope)
    case DeltaPropagation(from, reply, deltas)  ⇒ receiveDeltaPropagation(from, reply, deltas)
    case FlushChanges                           ⇒ receiveFlushChanges()
    case DeltaPropagationTick                   ⇒ receiveDeltaPropagationTick()
    case GossipTick                             ⇒ receiveGossipTick()
    case ClockTick                              ⇒ receiveClockTick()
    case Status(otherDigests, chunk, totChunks) ⇒ receiveStatus(otherDigests, chunk, totChunks)
    case Gossip(updatedData, sendBack)          ⇒ receiveGossip(updatedData, sendBack)
    case Subscribe(key, subscriber)             ⇒ receiveSubscribe(key, subscriber)
    case Unsubscribe(key, subscriber)           ⇒ receiveUnsubscribe(key, subscriber)
    case Terminated(ref)                        ⇒ receiveTerminated(ref)
    case MemberWeaklyUp(m)                      ⇒ receiveWeaklyUpMemberUp(m)
    case MemberUp(m)                            ⇒ receiveMemberUp(m)
    case MemberRemoved(m, _)                    ⇒ receiveMemberRemoved(m)
    case evt: MemberEvent                       ⇒ receiveOtherMemberEvent(evt.member)
    case UnreachableMember(m)                   ⇒ receiveUnreachable(m)
    case ReachableMember(m)                     ⇒ receiveReachable(m)
    case GetKeyIds                              ⇒ receiveGetKeyIds()
    case Delete(key, consistency, req)          ⇒ receiveDelete(key, consistency, req)
    case RemovedNodePruningTick                 ⇒ receiveRemovedNodePruningTick()
    case GetReplicaCount                        ⇒ receiveGetReplicaCount()
    case TestFullStateGossip(enabled)           ⇒ fullStateGossipEnabled = enabled
  }

  def receiveGet(key: KeyR, consistency: ReadConsistency, req: Option[Any]): Unit = {
    val localValue = getData(key.id)
    log.debug("Received Get for key [{}]", key)
    if (isLocalGet(consistency)) {
      val reply = localValue match {
        case Some(DataEnvelope(DeletedData, _, _)) ⇒ DataDeleted(key, req)
        case Some(DataEnvelope(data, _, _))        ⇒ GetSuccess(key, req)(data)
        case None                                  ⇒ NotFound(key, req)
      }
      replyTo ! reply
    } else
      context.actorOf(ReadAggregator.props(key, consistency, req, nodes, unreachable, localValue, replyTo)
        .withDispatcher(context.props.dispatcher))
  }

  def isLocalGet(readConsistency: ReadConsistency): Boolean =
    readConsistency match {
      case ReadLocal                    ⇒ true
      case _: ReadMajority | _: ReadAll ⇒ nodes.isEmpty
      case _                            ⇒ false
    }

  def receiveRead(key: KeyId): Unit = {
    replyTo ! ReadResult(getData(key))
  }

  def isLocalSender(): Boolean = !replyTo.path.address.hasGlobalScope

  def receiveUpdate(key: KeyR, modify: Option[ReplicatedData] ⇒ ReplicatedData,
                    writeConsistency: WriteConsistency, req: Option[Any]): Unit = {
    val localValue = getData(key.id)

    def deltaOrPlaceholder(d: DeltaReplicatedData): Option[ReplicatedDelta] = {
      d.delta match {
        case s @ Some(_) ⇒ s
        case None        ⇒ Some(NoDeltaPlaceholder)
      }
    }

    Try {
      localValue match {
        case Some(DataEnvelope(DeletedData, _, _)) ⇒ throw new DataDeleted(key, req)
        case Some(envelope @ DataEnvelope(existing, _, _)) ⇒
          modify(Some(existing)) match {
            case d: DeltaReplicatedData if deltaCrdtEnabled ⇒
              (envelope.merge(d.resetDelta.asInstanceOf[existing.T]), deltaOrPlaceholder(d))
            case d ⇒
              (envelope.merge(d.asInstanceOf[existing.T]), None)
          }
        case None ⇒ modify(None) match {
          case d: DeltaReplicatedData if deltaCrdtEnabled ⇒
            (DataEnvelope(d.resetDelta), deltaOrPlaceholder(d))
          case d ⇒ (DataEnvelope(d), None)
        }
      }
    } match {
      case Success((envelope, delta)) ⇒
        log.debug("Received Update for key [{}]", key)

        // handle the delta
        delta match {
          case Some(d) ⇒ deltaPropagationSelector.update(key.id, d)
          case None    ⇒ // not DeltaReplicatedData
        }

        // note that it's important to do deltaPropagationSelector.update before setData,
        // so that the latest delta version is used
        val newEnvelope = setData(key.id, envelope)

        val durable = isDurable(key.id)
        if (isLocalUpdate(writeConsistency)) {
          if (durable)
            durableStore ! Store(key.id, new DurableDataEnvelope(newEnvelope),
              Some(StoreReply(UpdateSuccess(key, req), StoreFailure(key, req), replyTo)))
          else
            replyTo ! UpdateSuccess(key, req)
        } else {
          val (writeEnvelope, writeDelta) = delta match {
            case Some(NoDeltaPlaceholder) ⇒ (newEnvelope, None)
            case Some(d: RequiresCausalDeliveryOfDeltas) ⇒
              val v = deltaPropagationSelector.currentVersion(key.id)
              (newEnvelope, Some(Delta(newEnvelope.copy(data = d), v, v)))
            case Some(d) ⇒ (newEnvelope.copy(data = d), None)
            case None    ⇒ (newEnvelope, None)
          }
          val writeAggregator =
            context.actorOf(WriteAggregator.props(key, writeEnvelope, writeDelta, writeConsistency,
              req, nodes, unreachable, replyTo, durable)
              .withDispatcher(context.props.dispatcher))
          if (durable) {
            durableStore ! Store(key.id, new DurableDataEnvelope(newEnvelope),
              Some(StoreReply(UpdateSuccess(key, req), StoreFailure(key, req), writeAggregator)))
          }
        }
      case Failure(e: DataDeleted[_]) ⇒
        log.debug("Received Update for deleted key [{}]", key)
        replyTo ! e
      case Failure(e) ⇒
        log.debug("Received Update for key [{}], failed: {}", key, e.getMessage)
        replyTo ! ModifyFailure(key, "Update failed: " + e.getMessage, e, req)
    }
  }

  def isDurable(key: KeyId): Boolean =
    durable(key) || (durableWildcards.nonEmpty && durableWildcards.exists(key.startsWith))

  def isLocalUpdate(writeConsistency: WriteConsistency): Boolean =
    writeConsistency match {
      case WriteLocal                     ⇒ true
      case _: WriteMajority | _: WriteAll ⇒ nodes.isEmpty
      case _                              ⇒ false
    }

  def receiveWrite(key: KeyId, envelope: DataEnvelope): Unit =
    writeAndStore(key, envelope, reply = true)

  def writeAndStore(key: KeyId, writeEnvelope: DataEnvelope, reply: Boolean): Unit = {
    write(key, writeEnvelope) match {
      case Some(newEnvelope) ⇒
        if (isDurable(key)) {
          val storeReply = if (reply) Some(StoreReply(WriteAck, WriteNack, replyTo)) else None
          durableStore ! Store(key, new DurableDataEnvelope(newEnvelope), storeReply)
        } else if (reply)
          replyTo ! WriteAck
      case None ⇒
        if (reply)
          replyTo ! WriteNack
    }
  }

  def write(key: KeyId, writeEnvelope: DataEnvelope): Option[DataEnvelope] = {
    getData(key) match {
      case someEnvelope @ Some(envelope) if envelope eq writeEnvelope ⇒ someEnvelope
      case Some(DataEnvelope(DeletedData, _, _))                      ⇒ Some(DeletedEnvelope) // already deleted
      case Some(envelope @ DataEnvelope(existing, _, _)) ⇒
        try {
          // DataEnvelope will mergeDelta when needed
          val merged = envelope.merge(writeEnvelope).addSeen(selfAddress)
          Some(setData(key, merged))
        } catch {
          case e: IllegalArgumentException ⇒
            log.warning(
              "Couldn't merge [{}], due to: {}", key, e.getMessage)
            None
        }
      case None ⇒
        // no existing data for the key
        val writeEnvelope2 =
          writeEnvelope.data match {
            case d: ReplicatedDelta ⇒
              val z = d.zero
              writeEnvelope.copy(data = z.mergeDelta(d.asInstanceOf[z.D]))
            case _ ⇒
              writeEnvelope
          }

        val writeEnvelope3 = writeEnvelope2.addSeen(selfAddress)
        Some(setData(key, writeEnvelope3))
    }
  }

  def receiveReadRepair(key: KeyId, writeEnvelope: DataEnvelope): Unit = {
    writeAndStore(key, writeEnvelope, reply = false)
    replyTo ! ReadRepairAck
  }

  def receiveGetKeyIds(): Unit = {
    val keys: Set[KeyId] = dataEntries.collect {
      case (key, (DataEnvelope(data, _, _), _)) if data != DeletedData ⇒ key
    }(collection.breakOut)
    replyTo ! GetKeyIdsResult(keys)
  }

  def receiveDelete(key: KeyR, consistency: WriteConsistency, req: Option[Any]): Unit = {
    getData(key.id) match {
      case Some(DataEnvelope(DeletedData, _, _)) ⇒
        // already deleted
        replyTo ! DataDeleted(key, req)
      case _ ⇒
        setData(key.id, DeletedEnvelope)
        val durable = isDurable(key.id)
        if (isLocalUpdate(consistency)) {
          if (durable)
            durableStore ! Store(key.id, new DurableDataEnvelope(DeletedEnvelope),
              Some(StoreReply(DeleteSuccess(key, req), StoreFailure(key, req), replyTo)))
          else
            replyTo ! DeleteSuccess(key, req)
        } else {
          val writeAggregator =
            context.actorOf(WriteAggregator.props(key, DeletedEnvelope, None, consistency, req, nodes, unreachable, replyTo, durable)
              .withDispatcher(context.props.dispatcher))
          if (durable) {
            durableStore ! Store(key.id, new DurableDataEnvelope(DeletedEnvelope),
              Some(StoreReply(DeleteSuccess(key, req), StoreFailure(key, req), writeAggregator)))
          }
        }
    }
  }

  def setData(key: KeyId, envelope: DataEnvelope): DataEnvelope = {
    val newEnvelope = {
      if (deltaCrdtEnabled) {
        val deltaVersions = envelope.deltaVersions
        val currVersion = deltaPropagationSelector.currentVersion(key)
        if (currVersion == 0L || currVersion == deltaVersions.versionAt(selfUniqueAddress))
          envelope
        else
          envelope.copy(deltaVersions = deltaVersions.merge(VersionVector(selfUniqueAddress, currVersion)))
      } else envelope
    }

    val dig =
      if (subscribers.contains(key) && !changed.contains(key)) {
        val oldDigest = getDigest(key)
        val dig = digest(newEnvelope)
        if (dig != oldDigest)
          changed += key // notify subscribers, later
        dig
      } else if (newEnvelope.data == DeletedData) DeletedDigest
      else LazyDigest

    dataEntries = dataEntries.updated(key, (newEnvelope, dig))
    if (newEnvelope.data == DeletedData)
      deltaPropagationSelector.delete(key)
    newEnvelope
  }

  def getDigest(key: KeyId): Digest = {
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
      val bytes = serializer.toBinary(envelope.withoutDeltaVersions)
      ByteString.fromArray(MessageDigest.getInstance("SHA-1").digest(bytes))
    }

  def getData(key: KeyId): Option[DataEnvelope] = dataEntries.get(key).map { case (envelope, _) ⇒ envelope }

  def getDeltaSeqNr(key: KeyId, fromNode: UniqueAddress): Long =
    dataEntries.get(key) match {
      case Some((DataEnvelope(_, _, deltaVersions), _)) ⇒ deltaVersions.versionAt(fromNode)
      case None                                         ⇒ 0L
    }

  def isNodeRemoved(node: UniqueAddress, keys: Iterable[KeyId]): Boolean = {
    removedNodes.contains(node) || (keys.exists(key ⇒ dataEntries.get(key) match {
      case Some((DataEnvelope(_, pruning, _), _)) ⇒ pruning.contains(node)
      case None                                   ⇒ false
    }))
  }

  def receiveFlushChanges(): Unit = {
    def notify(keyId: KeyId, subs: mutable.Set[ActorRef]): Unit = {
      val key = subscriptionKeys(keyId)
      getData(keyId) match {
        case Some(envelope) ⇒
          val msg = if (envelope.data == DeletedData) Deleted(key) else Changed(key)(envelope.data)
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

    changed = Set.empty[KeyId]
  }

  def receiveDeltaPropagationTick(): Unit = {
    deltaPropagationSelector.collectPropagations().foreach {
      case (node, deltaPropagation) ⇒
        // TODO split it to several DeltaPropagation if too many entries
        if (deltaPropagation.deltas.nonEmpty)
          replica(node) ! deltaPropagation
    }

    if (deltaPropagationSelector.propagationCount % deltaPropagationSelector.gossipIntervalDivisor == 0)
      deltaPropagationSelector.cleanupDeltaEntries()
  }

  def receiveDeltaPropagation(fromNode: UniqueAddress, reply: Boolean, deltas: Map[KeyId, Delta]): Unit =
    if (deltaCrdtEnabled) {
      try {
        val isDebugEnabled = log.isDebugEnabled
        if (isDebugEnabled)
          log.debug("Received DeltaPropagation from [{}], containing [{}]", fromNode.address,
            deltas.collect { case (key, Delta(_, fromSeqNr, toSeqNr)) ⇒ s"$key $fromSeqNr-$toSeqNr" }.mkString(", "))

        if (isNodeRemoved(fromNode, deltas.keys)) {
          // Late message from a removed node.
          // Drop it to avoid merging deltas that have been pruned on one side.
          if (isDebugEnabled) log.debug(
            "Skipping DeltaPropagation from [{}] because that node has been removed", fromNode.address)
        } else {
          deltas.foreach {
            case (key, Delta(envelope @ DataEnvelope(_: RequiresCausalDeliveryOfDeltas, _, _), fromSeqNr, toSeqNr)) ⇒
              val currentSeqNr = getDeltaSeqNr(key, fromNode)
              if (currentSeqNr >= toSeqNr) {
                if (isDebugEnabled) log.debug(
                  "Skipping DeltaPropagation from [{}] for [{}] because toSeqNr [{}] already handled [{}]",
                  fromNode.address, key, toSeqNr, currentSeqNr)
                if (reply) replyTo ! WriteAck
              } else if (fromSeqNr > (currentSeqNr + 1)) {
                if (isDebugEnabled) log.debug(
                  "Skipping DeltaPropagation from [{}] for [{}] because missing deltas between [{}-{}]",
                  fromNode.address, key, currentSeqNr + 1, fromSeqNr - 1)
                if (reply) replyTo ! DeltaNack
              } else {
                if (isDebugEnabled) log.debug(
                  "Applying DeltaPropagation from [{}] for [{}] with sequence numbers [{}], current was [{}]",
                  fromNode.address, key, s"$fromSeqNr-$toSeqNr", currentSeqNr)
                val newEnvelope = envelope.copy(deltaVersions = VersionVector(fromNode, toSeqNr))
                writeAndStore(key, newEnvelope, reply)
              }
            case (key, Delta(envelope, _, _)) ⇒
              // causal delivery of deltas not needed, just apply it
              writeAndStore(key, envelope, reply)
          }
        }
      } catch {
        case NonFatal(e) ⇒
          // catching in case we need to support rolling upgrades that are
          // mixing nodes with incompatible delta-CRDT types
          log.warning("Couldn't process DeltaPropagation from [{}] due to {}", fromNode, e)
      }
    } else {
      // !deltaCrdtEnabled
      if (reply) replyTo ! DeltaNack
    }

  def receiveGossipTick(): Unit = {
    if (fullStateGossipEnabled)
      selectRandomNode(nodes.union(weaklyUpNodes).toVector) foreach gossipTo
  }

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

  def receiveStatus(otherDigests: Map[KeyId, Digest], chunk: Int, totChunks: Int): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip status from [{}], chunk [{}] of [{}] containing [{}]", replyTo.path.address,
        (chunk + 1), totChunks, otherDigests.keys.mkString(", "))

    def isOtherDifferent(key: KeyId, otherDigest: Digest): Boolean = {
      val d = getDigest(key)
      d != NotFoundDigest && d != otherDigest
    }
    val otherDifferentKeys = otherDigests.collect {
      case (key, otherDigest) if isOtherDifferent(key, otherDigest) ⇒ key
    }
    val otherKeys = otherDigests.keySet
    val myKeys =
      if (totChunks == 1) dataEntries.keySet
      else dataEntries.keysIterator.filter(key ⇒ math.abs(key.hashCode) % totChunks == chunk).toSet
    val otherMissingKeys = myKeys diff otherKeys
    val keys = (otherDifferentKeys ++ otherMissingKeys).take(maxDeltaElements)
    if (keys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip to [{}], containing [{}]", replyTo.path.address, keys.mkString(", "))
      val g = Gossip(keys.map(k ⇒ k → getData(k).get)(collection.breakOut), sendBack = otherDifferentKeys.nonEmpty)
      replyTo ! g
    }
    val myMissingKeys = otherKeys diff myKeys
    if (myMissingKeys.nonEmpty) {
      if (log.isDebugEnabled)
        log.debug("Sending gossip status to [{}], requesting missing [{}]", replyTo.path.address, myMissingKeys.mkString(", "))
      val status = Status(myMissingKeys.map(k ⇒ k → NotFoundDigest)(collection.breakOut), chunk, totChunks)
      replyTo ! status
    }
  }

  def receiveGossip(updatedData: Map[KeyId, DataEnvelope], sendBack: Boolean): Unit = {
    if (log.isDebugEnabled)
      log.debug("Received gossip from [{}], containing [{}]", replyTo.path.address, updatedData.keys.mkString(", "))
    var replyData = Map.empty[KeyId, DataEnvelope]
    updatedData.foreach {
      case (key, envelope) ⇒
        val hadData = dataEntries.contains(key)
        writeAndStore(key, envelope, reply = false)
        if (sendBack) getData(key) match {
          case Some(d) ⇒
            if (hadData || d.pruning.nonEmpty)
              replyData = replyData.updated(key, d)
          case None ⇒
        }
    }
    if (sendBack && replyData.nonEmpty)
      replyTo ! Gossip(replyData, sendBack = false)
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
    if (ref == durableStore) {
      log.error("Stopping distributed-data Replicator because durable store terminated")
      context.stop(self)
    } else {
      val keys1 = subscribers.collect { case (k, s) if s.contains(ref) ⇒ k }
      keys1.foreach { key ⇒ subscribers.removeBinding(key, ref) }
      val keys2 = newSubscribers.collect { case (k, s) if s.contains(ref) ⇒ k }
      keys2.foreach { key ⇒ newSubscribers.removeBinding(key, ref) }

      (keys1 ++ keys2).foreach { key ⇒
        if (!subscribers.contains(key) && !newSubscribers.contains(key))
          subscriptionKeys -= key
      }
    }
  }

  def receiveWeaklyUpMemberUp(m: Member): Unit =
    if (matchingRole(m) && m.address != selfAddress)
      weaklyUpNodes += m.address

  def receiveMemberUp(m: Member): Unit =
    if (matchingRole(m)) {
      leader += m
      if (m.address != selfAddress) {
        nodes += m.address
        weaklyUpNodes -= m.address
      }
    }

  def receiveMemberRemoved(m: Member): Unit = {
    if (m.address == selfAddress)
      context stop self
    else if (matchingRole(m)) {
      leader -= m
      nodes -= m.address
      weaklyUpNodes -= m.address
      log.debug("adding removed node [{}] from MemberRemoved", m.uniqueAddress)
      removedNodes = removedNodes.updated(m.uniqueAddress, allReachableClockTime)
      unreachable -= m.address
      deltaPropagationSelector.cleanupRemovedNode(m.address)
    }
  }

  def receiveOtherMemberEvent(m: Member): Unit =
    if (matchingRole(m)) {
      // update changed status
      leader = (leader - m) + m
    }

  def receiveUnreachable(m: Member): Unit =
    if (matchingRole(m)) unreachable += m.address

  def receiveReachable(m: Member): Unit =
    if (matchingRole(m)) unreachable -= m.address

  def receiveClockTick(): Unit = {
    val now = System.nanoTime()
    if (unreachable.isEmpty)
      allReachableClockTime += (now - previousClockTime)
    previousClockTime = now
  }

  def receiveRemovedNodePruningTick(): Unit = {
    // See 'CRDT Garbage' section in Replicator Scaladoc for description of the process
    if (unreachable.isEmpty) {
      if (isLeader) {
        collectRemovedNodes()
        initRemovedNodePruning()
      }
      performRemovedNodePruning()
      deleteObsoletePruningPerformed()
    }
  }

  def collectRemovedNodes(): Unit = {
    val knownNodes = nodes union weaklyUpNodes union removedNodes.keySet.map(_.address)
    val newRemovedNodes =
      dataEntries.foldLeft(Set.empty[UniqueAddress]) {
        case (acc, (_, (envelope @ DataEnvelope(data: RemovedNodePruning, _, _), _))) ⇒
          acc union data.modifiedByNodes.filterNot(n ⇒ n == selfUniqueAddress || knownNodes(n.address))
        case (acc, _) ⇒
          acc
      }

    newRemovedNodes.foreach { n ⇒
      log.debug("Adding removed node [{}] from data", n)
      removedNodes = removedNodes.updated(n, allReachableClockTime)
    }
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
          log.debug("Initiated pruning of [{}] for data key [{}] to [{}]", removed, key, selfUniqueAddress)
          setData(key, newEnvelope)
        }

        if (envelope.needPruningFrom(removed)) {
          envelope.data match {
            case dataWithRemovedNodePruning: RemovedNodePruning ⇒
              envelope.pruning.get(removed) match {
                case None ⇒ init()
                case Some(PruningInitialized(owner, _)) if owner != selfUniqueAddress ⇒ init()
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
    val allNodes = nodes union weaklyUpNodes
    val pruningPerformed = PruningPerformed(System.currentTimeMillis() + pruningMarkerTimeToLive.toMillis)
    val durablePruningPerformed = PruningPerformed(System.currentTimeMillis() + durablePruningMarkerTimeToLive.toMillis)
    dataEntries.foreach {
      case (key, (envelope @ DataEnvelope(data: RemovedNodePruning, pruning, _), _)) ⇒
        pruning.foreach {
          case (removed, PruningInitialized(owner, seen)) if owner == selfUniqueAddress
            && (allNodes.isEmpty || allNodes.forall(seen)) ⇒
            val newEnvelope = envelope.prune(removed, if (isDurable(key)) durablePruningPerformed else pruningPerformed)
            log.debug("Perform pruning of [{}] from [{}] to [{}]", key, removed, selfUniqueAddress)
            setData(key, newEnvelope)
            if ((newEnvelope.data ne data) && isDurable(key))
              durableStore ! Store(key, new DurableDataEnvelope(newEnvelope), None)
          case _ ⇒
        }
      case _ ⇒ // deleted, or pruning not needed
    }
  }

  def deleteObsoletePruningPerformed(): Unit = {
    val currentTime = System.currentTimeMillis()
    dataEntries.foreach {
      case (key, (envelope @ DataEnvelope(_: RemovedNodePruning, pruning, _), _)) ⇒
        val newEnvelope = pruning.foldLeft(envelope) {
          case (acc, (removed, p: PruningPerformed)) if p.isObsolete(currentTime) ⇒
            log.debug("Removing obsolete pruning marker for [{}] in [{}]", removed, key)
            removedNodes -= removed
            acc.copy(pruning = acc.pruning - removed)
          case (acc, _) ⇒ acc
        }
        if (newEnvelope ne envelope)
          setData(key, newEnvelope)

      case _ ⇒ // deleted, or pruning not needed
    }

  }

  def receiveGetReplicaCount(): Unit = {
    // selfAddress is not included in the set
    replyTo ! ReplicaCount(nodes.size + 1)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ReadWriteAggregator {
  case object SendToSecondary
  val MaxSecondaryNodes = 10

  def calculateMajorityWithMinCap(minCap: Int, numberOfNodes: Int): Int = {
    if (numberOfNodes <= minCap) {
      numberOfNodes
    } else {
      val majority = numberOfNodes / 2 + 1
      if (majority <= minCap) minCap
      else majority
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] abstract class ReadWriteAggregator extends Actor {
  import ReadWriteAggregator._

  def timeout: FiniteDuration
  def nodes: Set[Address]
  def unreachable: Set[Address]
  def reachableNodes: Set[Address] = nodes diff unreachable

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
      // Prefer to use reachable nodes over the unreachable nodes first
      val orderedNodes = scala.util.Random.shuffle(reachableNodes.toVector) ++ scala.util.Random.shuffle(unreachable.toVector)
      val (p, s) = orderedNodes.splitAt(primarySize)
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
@InternalApi private[akka] object WriteAggregator {
  def props(
    key:         KeyR,
    envelope:    Replicator.Internal.DataEnvelope,
    delta:       Option[Replicator.Internal.Delta],
    consistency: Replicator.WriteConsistency,
    req:         Option[Any],
    nodes:       Set[Address],
    unreachable: Set[Address],
    replyTo:     ActorRef,
    durable:     Boolean): Props =
    Props(new WriteAggregator(key, envelope, delta, consistency, req, nodes, unreachable, replyTo, durable))
      .withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class WriteAggregator(
  key:                      KeyR,
  envelope:                 Replicator.Internal.DataEnvelope,
  delta:                    Option[Replicator.Internal.Delta],
  consistency:              Replicator.WriteConsistency,
  req:                      Option[Any],
  override val nodes:       Set[Address],
  override val unreachable: Set[Address],
  replyTo:                  ActorRef,
  durable:                  Boolean) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._
  import ReadWriteAggregator._

  val selfUniqueAddress = Cluster(context.system).selfUniqueAddress

  override def timeout: FiniteDuration = consistency.timeout

  override val doneWhenRemainingSize = consistency match {
    case WriteTo(n, _) ⇒ nodes.size - (n - 1)
    case _: WriteAll   ⇒ 0
    case WriteMajority(_, minCap) ⇒
      val N = nodes.size + 1
      val w = calculateMajorityWithMinCap(minCap, N)
      N - w
    case WriteLocal ⇒
      throw new IllegalArgumentException("WriteLocal not supported by WriteAggregator")
  }

  val writeMsg = Write(key.id, envelope)
  val deltaMsg = delta match {
    case None    ⇒ None
    case Some(d) ⇒ Some(DeltaPropagation(selfUniqueAddress, reply = true, Map(key.id → d)))
  }

  var gotLocalStoreReply = !durable
  var gotWriteNackFrom = Set.empty[Address]

  override def preStart(): Unit = {
    val msg = deltaMsg match {
      case Some(d) ⇒ d
      case None    ⇒ writeMsg
    }
    primaryNodes.foreach { replica(_) ! msg }

    if (isDone) reply(isTimeout = false)
  }

  def receive: Receive = {
    case WriteAck ⇒
      remaining -= senderAddress()
      if (isDone) reply(isTimeout = false)
    case WriteNack ⇒
      gotWriteNackFrom += senderAddress()
      if (isDone) reply(isTimeout = false)
    case DeltaNack ⇒
    // ok, will be retried with full state

    case _: Replicator.UpdateSuccess[_] ⇒
      gotLocalStoreReply = true
      if (isDone) reply(isTimeout = false)
    case f: Replicator.StoreFailure[_] ⇒
      gotLocalStoreReply = true
      gotWriteNackFrom += selfUniqueAddress.address
      if (isDone) reply(isTimeout = false)

    case SendToSecondary ⇒
      deltaMsg match {
        case None ⇒
        case Some(d) ⇒
          // Deltas must be applied in order and we can't keep track of ordering of
          // simultaneous updates so there is a chance that the delta could not be applied.
          // Try again with the full state to the primary nodes that have not acked.
          primaryNodes.toSet.intersect(remaining).foreach { replica(_) ! writeMsg }
      }
      secondaryNodes.foreach { replica(_) ! writeMsg }
    case ReceiveTimeout ⇒
      reply(isTimeout = true)
  }

  def senderAddress(): Address = sender().path.address

  def isDone: Boolean =
    gotLocalStoreReply &&
      (remaining.size <= doneWhenRemainingSize || (remaining diff gotWriteNackFrom).isEmpty ||
        notEnoughNodes)

  def notEnoughNodes: Boolean =
    doneWhenRemainingSize < 0 || nodes.size < doneWhenRemainingSize

  def reply(isTimeout: Boolean): Unit = {
    val isDelete = envelope.data == DeletedData
    val isSuccess = remaining.size <= doneWhenRemainingSize && !notEnoughNodes
    val isTimeoutOrNotEnoughNodes = isTimeout || notEnoughNodes || gotWriteNackFrom.isEmpty

    val replyMsg =
      if (isSuccess && isDelete) DeleteSuccess(key, req)
      else if (isSuccess) UpdateSuccess(key, req)
      else if (isTimeoutOrNotEnoughNodes && isDelete) ReplicationDeleteFailure(key, req)
      else if (isTimeoutOrNotEnoughNodes || !durable) UpdateTimeout(key, req)
      else StoreFailure(key, req)

    replyTo.tell(replyMsg, context.parent)
    context.stop(self)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ReadAggregator {
  def props(
    key:         KeyR,
    consistency: Replicator.ReadConsistency,
    req:         Option[Any],
    nodes:       Set[Address],
    unreachable: Set[Address],
    localValue:  Option[Replicator.Internal.DataEnvelope],
    replyTo:     ActorRef): Props =
    Props(new ReadAggregator(key, consistency, req, nodes, unreachable, localValue, replyTo))
      .withDeploy(Deploy.local)

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ReadAggregator(
  key:                      KeyR,
  consistency:              Replicator.ReadConsistency,
  req:                      Option[Any],
  override val nodes:       Set[Address],
  override val unreachable: Set[Address],
  localValue:               Option[Replicator.Internal.DataEnvelope],
  replyTo:                  ActorRef) extends ReadWriteAggregator {

  import Replicator._
  import Replicator.Internal._
  import ReadWriteAggregator._

  override def timeout: FiniteDuration = consistency.timeout

  var result = localValue
  override val doneWhenRemainingSize = consistency match {
    case ReadFrom(n, _) ⇒ nodes.size - (n - 1)
    case _: ReadAll     ⇒ 0
    case ReadMajority(_, minCap) ⇒
      val N = nodes.size + 1
      val r = calculateMajorityWithMinCap(minCap, N)
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
        if (envelope.data == DeletedData) DataDeleted(key, req)
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

