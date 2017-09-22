/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.cluster.sharding

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.cluster.sharding.ShardCoordinator.{ LeastShardAllocationStrategy, ShardAllocationStrategy }
import akka.cluster.sharding.{ ClusterSharding ⇒ UntypedClusterSharding, ShardRegion ⇒ UntypedShardRegion }
import akka.typed.cluster.Cluster
import akka.typed.internal.adapter.{ ActorRefAdapter, ActorSystemAdapter }
import akka.typed.scaladsl.adapter.PropsAdapter
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }

import scala.language.implicitConversions
import scala.reflect.ClassTag

/**
 * Default envelope type that may be used with Cluster Sharding.
 *
 * Cluster Sharding provides a default [[HashCodeMessageExtractor]] that is able to handle
 * these types of messages, by hashing the entityId into into the shardId. It is not the only,
 * but a convenient way to send envelope-wrapped messages via cluster sharding.
 *
 * The alternative way of routing messages through sharding is to not use envelopes,
 * and have the message types themselfs carry identifiers.
 */
final case class ShardingEnvelope[A](entityId: String, message: A) // TODO think if should remain a case class

/** Allows starting a specific Sharded Entity by its entity identifier */
object StartEntity {

  /**
   * Returns [[ShardingEnvelope]] which can be sent via Cluster Sharding in order to wake up the
   * specified (by `entityId`) Sharded Entity, ''without'' delivering a real message to it.
   */
  def apply[A](entityId: String): ShardingEnvelope[A] =
    new ShardingEnvelope[A](entityId, null.asInstanceOf[A]) // TODO should we instead sub-class here somehow?

  /**
   * Java API
   *
   * Returns [[ShardingEnvelope]] which can be sent via Cluster Sharding in order to wake up the
   * specified (by `entityId`) Sharded Entity, ''without'' delivering a real message to it.
   */
  def create[A](msgClass: Class[A], entityId: String): ShardingEnvelope[A] =
    apply[A](entityId)
}

object ShardingMessageExtractor {

  /**
   * Scala API:
   *
   * Create the default message extractor, using envelopes to identify what entity a message is for
   * and the hashcode of the entityId to decide which shard an entity belongs to.
   *
   * This is recommended since it does not force details about sharding into the entity protocol
   */
  def apply[A](maxNumberOfShards: Int): ShardingMessageExtractor[ShardingEnvelope[A], A] =
    new HashCodeMessageExtractor[A](maxNumberOfShards)

  /**
   * Create a message extractor for a protocol where the entity id is available in each message.
   */
  def noEnvelope[A](
    maxNumberOfShards: Int,
    extractEntityId:   A ⇒ String): ShardingMessageExtractor[A, A] =
    new HashCodeNoEnvelopeMessageExtractor[A](maxNumberOfShards) {
      // TODO catch MatchError here and return null for those to yield an "unhandled" when partial functions are used?
      def entityId(message: A) = extractEntityId(message)
    }

}

/**
 * Entirely customizable typed message extractor. Prefer [[HashCodeMessageExtractor]] or [[HashCodeNoEnvelopeMessageExtractor]]
 * if possible.
 *
 * @tparam E Possibly an Envelope around the messages accepted by the entity actor, is the same as `A` if there is no
 *           envelope.
 * @tparam A The type of message accepted by the entity actor
 */
trait ShardingMessageExtractor[E, A] {

  /**
   * Extract the entity id from an incoming `message`. If `null` is returned
   * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   */
  def entityId(message: E): String

  /**
   * Extract the message to send to the entity from an incoming `message`.
   * Note that the extracted message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entity actor.
   *
   * If the returned value is `null`, and the entity isn't running yet the entity will be started
   * but no message will be delivered to it.
   */
  def entityMessage(message: E): A // TODO "unwrapMessage" is how I'd call it?

  /**
   * Extract the entity id from an incoming `message`. Only messages that passed the [[#entityId]]
   * function will be used as input to this function.
   */
  def shardId(message: E): String
}

/**
 * Java API:
 *
 * Default message extractor type, using envelopes to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
final class HashCodeMessageExtractor[A](maxNumberOfShards: Int) extends ShardingMessageExtractor[ShardingEnvelope[A], A] {
  def entityId(envelope: ShardingEnvelope[A]): String = envelope.entityId
  def entityMessage(envelope: ShardingEnvelope[A]): A = envelope.message
  def shardId(envelope: ShardingEnvelope[A]): String = (math.abs(envelope.entityId.hashCode) % maxNumberOfShards).toString
}

/**
 * Java API:
 *
 * Default message extractor type, using a property of the message to identify what entity a message is for
 * and the hashcode of the entityId to decide which shard an entity belongs to.
 *
 * This is recommended since it does not force details about sharding into the entity protocol
 *
 * @tparam A The type of message accepted by the entity actor
 */
abstract class HashCodeNoEnvelopeMessageExtractor[A](maxNumberOfShards: Int) extends ShardingMessageExtractor[A, A] {
  final def entityMessage(message: A): A = message
  def shardId(message: A): String = {
    val id = entityId(message)
    if (id != null) (math.abs(id.hashCode) % maxNumberOfShards).toString
    else null
  }

  override def toString = s"HashCodeNoEnvelopeMessageExtractor($maxNumberOfShards)"
}

/**
 * The key of an entity type, the `name` must be unique.
 */
abstract class EntityTypeKey[T] {
  def name: String
}

object EntityTypeKey {
  /**
   * Scala API: Creates an `EntityTypeKey`. The `name` must be unique.
   */
  def apply[T](name: String)(implicit tTag: ClassTag[T]): EntityTypeKey[T] =
    AdaptedClusterShardingImpl.EntityTypeKeyImpl(name, implicitly[ClassTag[T]].runtimeClass.getName)

  /**
   * Java API: Creates an `EntityTypeKey`. The `name` must be unique.
   */
  def create[T](messageClass: Class[T], name: String): EntityTypeKey[T] =
    AdaptedClusterShardingImpl.EntityTypeKeyImpl(name, messageClass.getName)

}

object ClusterSharding extends ExtensionId[ClusterSharding] {

  override def createExtension(system: ActorSystem[_]): ClusterSharding =
    new AdaptedClusterShardingImpl(system)

  /** Java API */
  def get(system: ActorSystem[_]): ClusterSharding = apply(system)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object AdaptedClusterShardingImpl {
  final case class EntityTypeKeyImpl[T](name: String, messageClassName: String) extends EntityTypeKey[T] {
    override def toString: String = s"EntityTypeKey[$messageClassName]($name)"
  }
}

/** INTERNAL API */
@InternalApi
final class AdaptedClusterShardingImpl(system: ActorSystem[_]) extends ClusterSharding {
  import akka.typed.scaladsl.adapter._
  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted untyped actor systems can be used for cluster features")

  private val cluster = Cluster(system)
  private val untypedSystem = system.toUntyped
  private val untypedSharding = akka.cluster.sharding.ClusterSharding(untypedSystem)

  override def spawn[A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]] = {
    val extractor = new HashCodeMessageExtractor[A](maxNumberOfShards)
    spawn(behavior, entityProps, typeKey, settings, extractor, defaultShardAllocationStrategy(settings), handOffStopMessage)
  }

  override def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    handOffStopMessage: A): ActorRef[E] =
    spawn(behavior, entityProps, typeKey, settings, messageExtractor, defaultShardAllocationStrategy(settings), handOffStopMessage)

  override def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    extractor:          ShardingMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A): ActorRef[E] = {

    val untypedSettings = ClusterShardingSettings.toUntypedSettings(settings)

    val ref =
      if (settings.shouldHostShard(cluster)) {
        system.log.info("Starting Shard Region [{}]...")
        untypedSharding.start(
          typeKey.name,
          PropsAdapter(behavior, entityProps),
          untypedSettings,
          extractor, extractor,
          defaultShardAllocationStrategy(settings),
          handOffStopMessage)
      } else {
        system.log.info("Starting Shard Region Proxy [{}] (no actors will be hosted on this node)...")

        untypedSharding.startProxy(
          typeKey.name,
          settings.role,
          dataCenter = None, // TODO what about the multi-dc value here?
          extractShardId = extractor,
          extractEntityId = extractor)
      }

    ActorRefAdapter(ref)
  }

  override def entityRefFor[A](typeKey: EntityTypeKey[A], entityId: String): EntityRef[A] = {
    new AdaptedEntityRefImpl[A](untypedSharding.shardRegion(typeKey.name), entityId)
  }

  override def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy = {
    val threshold = settings.tuningParameters.leastShardAllocationRebalanceThreshold
    val maxSimultaneousRebalance = settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance
    new LeastShardAllocationStrategy(threshold, maxSimultaneousRebalance)
  }

  // --- extractor conversions ---
  @InternalApi
  private implicit def convertExtractEntityId[E, A](extractor: ShardingMessageExtractor[E, A]): UntypedShardRegion.ExtractEntityId = {
    // TODO what if msg was null
    case msg: E if extractor.entityId(msg.asInstanceOf[E]) ne null ⇒
      // we're evaluating entityId twice, I wonder if we could do it just once (same was in old sharding's Java DSL)

      (extractor.entityId(msg.asInstanceOf[E]), extractor.entityMessage(msg.asInstanceOf[E]))
  }
  @InternalApi
  private implicit def convertExtractShardId[E, A](extractor: ShardingMessageExtractor[E, A]): UntypedShardRegion.ExtractShardId = {
    case msg: E ⇒ extractor.shardId(msg)
  }
}

@DoNotInherit
sealed trait ClusterSharding extends Extension {

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * Messages are sent to the entities by wrapping the messages in a [[ShardingEnvelope]] with the entityId of the
   * recipient actor.
   * A [[HashCodeMessageExtractor]] will be used for extracting entityId and shardId
   * [[akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy]] will be used for shard allocation strategy.
   *
   * @param behavior The behavior for entities
   * @param typeKey A key that uniquely identifies the type of entity in this cluster
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam A The type of command the entity accepts
   */
  // TODO: FYI, I think it would be very good to have rule that "behavior, otherstuff"
  // TODO: or "behavior, props, otherstuff" be the consistent style we want to promote in parameter ordering, WDYT?
  def spawn[A](
    behavior:           Behavior[A],
    props:              Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]]

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeKey A key that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param allocationStrategy Allocation strategy which decides on which nodes to allocate new shards
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A): ActorRef[E]

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeKey A key that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeKey:            EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    handOffStopMessage: A): ActorRef[E]

  /**
   * Create an `ActorRef`-like reference to a specific sharded entity.
   * Currently you have to correctly specify the type of messages the target can handle.
   *
   * Messages sent through this [[EntityRef]] will be wrapped in a [[ShardingEnvelope]] including the
   * here provided `entityId`.
   *
   * FIXME a more typed version of this API will be explored in https://github.com/akka/akka/issues/23690
   *
   * For in-depth documentation of its semantics, see [[EntityRef]].
   */
  def entityRefFor[A](typeKey: EntityTypeKey[A], entityId: String): EntityRef[A]

  /** The default ShardAllocationStrategy currently is [[LeastShardAllocationStrategy]] however could be changed in the future. */
  def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy
}
