/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.cluster.sharding

import scala.language.implicitConversions
import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.{ LeastShardAllocationStrategy, ShardAllocationStrategy }
import akka.cluster.sharding.ShardRegion.ExtractShardId
import akka.cluster.sharding.{ ClusterSharding ⇒ UntypedClusterSharding }
import akka.cluster.sharding.{ ShardRegion ⇒ UntypedShardRegion }
import akka.typed.internal.adapter.{ ActorRefAdapter, ActorSystemAdapter }
import akka.typed.scaladsl.adapter.PropsAdapter
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }

import scala.reflect.ClassTag
import scala.util.Try

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
    extractEntityId:   A ⇒ String
  ): ShardingMessageExtractor[A, A] =
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

object ClusterSharding extends ExtensionId[ClusterSharding] {

  override def createExtension(system: ActorSystem[_]): ClusterSharding =
    new ClusterShardingImpl(system)

  /** Java API */
  def get(system: ActorSystem[_]): ClusterSharding = apply(system)
}

final class ClusterShardingImpl(system: ActorSystem[_]) extends ClusterSharding {
  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted untyped actor systems can be used for cluster features")

  private val untypedSystem = ActorSystemAdapter.toUntyped(system)
  private val untypedSharding = akka.cluster.sharding.ClusterSharding(untypedSystem)

  override def spawn[A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]] = {
    val extractor = new HashCodeMessageExtractor[A](10)
    spawn(behavior, entityProps, typeName, settings, extractor, defaultShardAllocationStrategy(settings), handOffStopMessage)
  }

  override def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    handOffStopMessage: A): ActorRef[E] =
    spawn(behavior, entityProps, typeName, settings, messageExtractor, defaultShardAllocationStrategy(settings), handOffStopMessage)

  override def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    extractor:          ShardingMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A): ActorRef[E] = {

    println(s"extractor = $extractor")
    val untypedSettings = ClusterShardingSettings.toUntypedSettings(settings)

    val ref = untypedSharding.start(
      typeName,
      PropsAdapter(behavior, entityProps),
      untypedSettings,
      convertExtractEntityId[E, A](extractor), convertExtractShardId[E, A](extractor),
      defaultShardAllocationStrategy(settings),
      handOffStopMessage
    )

    ActorRefAdapter(ref)
  }

  def entityRefFor[A](typeName: String, entityId: String): ShardedEntityRef[A] = {
    ???
  }

  override def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy = {
    val threshold = settings.tuningParameters.leastShardAllocationRebalanceThreshold
    val maxSimultaneousRebalance = settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance
    new LeastShardAllocationStrategy(threshold, maxSimultaneousRebalance)
  }

  // --- extractor conversions --- 
  @InternalApi
  private def convertExtractEntityId[E, A](extractor: ShardingMessageExtractor[E, A]): UntypedShardRegion.ExtractEntityId =
    {
      //    // TODO what if msg was null
      //    case msg if (Try(msg.asInstanceOf[E]).isSuccess && (extractor.entityId(msg.asInstanceOf[E]) ne null)) ⇒
      //      // we're evaluating entityId twice, I wonder if we could do it just once (same was in old sharding's Java DSL)
      //
      //      (extractor.entityId(msg.asInstanceOf[E]), extractor.entityMessage(msg.asInstanceOf[E]))

      new PartialFunction[Any, (String, A)] {
        override def isDefinedAt(x: Any) = {
          println(s"try entity id for = ${x} ==== ${Try(x.asInstanceOf[E]).isSuccess}")
          Try(x.asInstanceOf[E]).isSuccess
        }

        override def apply(msg: Any): (String, A) =
          (extractor.entityId(msg.asInstanceOf[E]), extractor.entityMessage(msg.asInstanceOf[E]))
      }
    }
  @InternalApi
  private def convertExtractShardId[E, A](extractor: ShardingMessageExtractor[E, A]): UntypedShardRegion.ExtractShardId = {
    //    case msg: E ⇒ extractor.shardId(msg)
    //    case _ ⇒ null // FIXME wrong type, should be impossible in normal usage, log it, make it unhandled?
    new PartialFunction[Any, String] {
      override def isDefinedAt(x: Any) = {
        println(s"try shard id for = ${x} ==== ${Try(x.asInstanceOf[E]).isSuccess}")
        Try(x.asInstanceOf[E]).isSuccess
      }

      override def apply(msg: Any): String =
        extractor.shardId(msg.asInstanceOf[E])
    }
  }
}

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
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam A The type of command the entity accepts
   */
  // TODO: FYI, I think it would be very good to have rule that "behavior, otherstuff" 
  // TODO: or "behavior, props, otherstuff" be the consistent style we want to promote in parameter ordering, WDYT?
  def spawn[A](
    behavior:           Behavior[A],
    props:              Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]]

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param allocationStrategy Allocation strategy which decides on which nodes to allocate new shards
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A
  ): ActorRef[E]

  /**
   * Spawn a shard region or a proxy depending on if the settings require role and if this node has such a role.
   *
   * @param behavior The behavior for entities
   * @param typeName A name that uniquely identifies the type of entity in this cluster
   * @param entityProps Props to apply when starting an entity
   * @param handOffStopMessage Message sent to an entity to tell it to stop
   * @tparam E A possible envelope around the message the entity accepts
   * @tparam A The type of command the entity accepts
   */
  def spawn[E, A](
    behavior:           Behavior[A],
    entityProps:        Props,
    typeName:           String,
    settings:           ClusterShardingSettings,
    messageExtractor:   ShardingMessageExtractor[E, A],
    handOffStopMessage: A
  ): ActorRef[E]

  /**
   * Create an `ActorRef`-like reference to a specific sharded entity.
   * Messages sent to it will be wrapped in a [[ShardingEnvelope]] and passed to the local shard region or proxy.
   *
   * Note: FIXME explain why it's not the same as actor ref
   *
   * This way of addressing Sharded Entities
   */
  def entityRefFor[A](typeName: String, entityId: String): ShardedEntityRef[A]
  // FIXME this is not so nice to use from Java, a class param would be welcome there

  /** The default ShardAllocationStrategy currently is [[LeastShardAllocationStrategy]] however could be changed in the future. */
  def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy
}
