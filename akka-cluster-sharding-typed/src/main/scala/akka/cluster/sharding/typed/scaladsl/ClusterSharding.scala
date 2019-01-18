/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed
package scaladsl

import scala.concurrent.Future
import scala.reflect.ClassTag

import akka.util.Timeout
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.ExtensionSetup
import akka.actor.typed.RecipientRef
import akka.actor.typed.Props
import akka.actor.typed.internal.InternalRecipientRef
import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.typed.internal.ClusterShardingImpl
import akka.cluster.sharding.typed.internal.EntityTypeKeyImpl
import akka.cluster.sharding.ShardRegion.{ StartEntity ⇒ UntypedStartEntity }
import akka.persistence.typed.PersistenceId

object ClusterSharding extends ExtensionId[ClusterSharding] {

  override def createExtension(system: ActorSystem[_]): ClusterSharding =
    new ClusterShardingImpl(system)

  /**
   * When an entity is created an `ActorRef[ShardCommand]` is passed to the
   * factory method. The entity can request passivation by sending the [[Passivate]]
   * message to this ref. Sharding will then send back the specified
   * `stopMessage` message to the entity, which is then supposed to stop itself.
   *
   * Not for user extension.
   */
  @DoNotInherit trait ShardCommand

  /**
   * The entity can request passivation by sending the [[Passivate]] message
   * to the `ActorRef[ShardCommand]` that was passed in to the factory method
   * when creating the entity. Sharding will then send back the specified
   * `stopMessage` message to the entity, which is then supposed to stop
   * itself.
   */
  final case class Passivate[M](entity: ActorRef[M]) extends ShardCommand with javadsl.ClusterSharding.ShardCommand

}

/**
 * This extension provides sharding functionality of actors in a cluster.
 * The typical use case is when you have many stateful actors that together consume
 * more resources (e.g. memory) than fit on one machine. You need to distribute them across
 * several nodes in the cluster and you want to be able to interact with them using their
 * logical identifier, but without having to care about their physical location in the cluster,
 * which might also change over time. It could for example be actors representing Aggregate Roots in
 * Domain-Driven Design terminology. Here we call these actors "entities". These actors
 * typically have persistent (durable) state, but this feature is not limited to
 * actors with persistent state.
 *
 * In this context sharding means that actors with an identifier, so called entities,
 * can be automatically distributed across multiple nodes in the cluster. Each entity
 * actor runs only at one place, and messages can be sent to the entity without requiring
 * the sender to know the location of the destination actor. This is achieved by sending
 * the messages via a `ShardRegion` actor provided by this extension, which knows how
 * to route the message with the entity id to the final destination.
 *
 * This extension is supposed to be used by first, typically at system startup on each node
 * in the cluster, registering the supported entity types with the [[ClusterSharding#init]]
 * method, which returns the `ShardRegion` actor reference for a named entity type.
 * Messages to the entities are always sent via that `ActorRef`, i.e. the local `ShardRegion`.
 * Messages can also be sent via the [[EntityRef]] retrieved with [[ClusterSharding#entityRefFor]],
 * which will also send via the local `ShardRegion`.
 *
 * Some settings can be configured as described in the `akka.cluster.sharding`
 * section of the `reference.conf`.
 *
 * The `ShardRegion` actor is started on each node in the cluster, or group of nodes
 * tagged with a specific role. The `ShardRegion` is created with a [[ShardingMessageExtractor]]
 * to extract the entity identifier and the shard identifier from incoming messages.
 * A shard is a group of entities that will be managed together. For the first message in a
 * specific shard the `ShardRegion` requests the location of the shard from a central coordinator,
 * the [[akka.cluster.sharding.ShardCoordinator]]. The `ShardCoordinator` decides which `ShardRegion`
 * owns the shard. The `ShardRegion` receives the decided home of the shard
 * and if that is the `ShardRegion` instance itself it will create a local child
 * actor representing the entity and direct all messages for that entity to it.
 * If the shard home is another `ShardRegion` instance messages will be forwarded
 * to that `ShardRegion` instance instead. While resolving the location of a
 * shard incoming messages for that shard are buffered and later delivered when the
 * shard location is known. Subsequent messages to the resolved shard can be delivered
 * to the target destination immediately without involving the `ShardCoordinator`.
 *
 * To make sure that at most one instance of a specific entity actor is running somewhere
 * in the cluster it is important that all nodes have the same view of where the shards
 * are located. Therefore the shard allocation decisions are taken by the central
 * `ShardCoordinator`, which is running as a cluster singleton, i.e. one instance on
 * the oldest member among all cluster nodes or a group of nodes tagged with a specific
 * role. The oldest member can be determined by [[akka.cluster.Member#isOlderThan]].
 *
 * To be able to use newly added members in the cluster the coordinator facilitates rebalancing
 * of shards, i.e. migrate entities from one node to another. In the rebalance process the
 * coordinator first notifies all `ShardRegion` actors that a handoff for a shard has started.
 * That means they will start buffering incoming messages for that shard, in the same way as if the
 * shard location is unknown. During the rebalance process the coordinator will not answer any
 * requests for the location of shards that are being rebalanced, i.e. local buffering will
 * continue until the handoff is completed. The `ShardRegion` responsible for the rebalanced shard
 * will stop all entities in that shard by sending the `handOffMessage` to them. When all entities have
 * been terminated the `ShardRegion` owning the entities will acknowledge the handoff as completed
 * to the coordinator. Thereafter the coordinator will reply to requests for the location of
 * the shard and thereby allocate a new home for the shard and then buffered messages in the
 * `ShardRegion` actors are delivered to the new location. This means that the state of the entities
 * are not transferred or migrated. If the state of the entities are of importance it should be
 * persistent (durable), e.g. with `akka-persistence`, so that it can be recovered at the new
 * location.
 *
 * The logic that decides which shards to rebalance is defined in a plugable shard
 * allocation strategy. The default implementation [[akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy]]
 * picks shards for handoff from the `ShardRegion` with most number of previously allocated shards.
 * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
 * i.e. new members in the cluster. There is a configurable threshold of how large the difference
 * must be to begin the rebalancing. This strategy can be replaced by an application specific
 * implementation.
 *
 * The state of shard locations in the `ShardCoordinator` is stored with `akka-distributed-data` or
 * `akka-persistence` to survive failures. When a crashed or unreachable coordinator
 * node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
 * actor will take over and the state is recovered. During such a failure period shards
 * with known location are still available, while messages for new (unknown) shards
 * are buffered until the new `ShardCoordinator` becomes available.
 *
 * As long as a sender uses the same `ShardRegion` actor to deliver messages to an entity
 * actor the order of the messages is preserved. As long as the buffer limit is not reached
 * messages are delivered on a best effort basis, with at-most once delivery semantics,
 * in the same way as ordinary message sending. Reliable end-to-end messaging, with
 * at-least-once semantics can be added by using `AtLeastOnceDelivery` in `akka-persistence`.
 *
 * Some additional latency is introduced for messages targeted to new or previously
 * unused shards due to the round-trip to the coordinator. Rebalancing of shards may
 * also add latency. This should be considered when designing the application specific
 * shard resolution, e.g. to avoid too fine grained shards.
 *
 * The `ShardRegion` actor can also be started in proxy only mode, i.e. it will not
 * host any entities itself, but knows how to delegate messages to the right location.
 *
 * If the state of the entities are persistent you may stop entities that are not used to
 * reduce memory consumption. This is done by the application specific implementation of
 * the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
 * If a message is already enqueued to the entity when it stops itself the enqueued message
 * in the mailbox will be dropped. To support graceful passivation without losing such
 * messages the entity actor can send [[ClusterSharding.Passivate]] to the `ActorRef[ShardCommand]`
 * that was passed in to the factory method when creating the entity..
 * The specified `stopMessage` message will be sent back to the entity, which is
 * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
 * between reception of `Passivate` and termination of the entity. Such buffered messages
 * are thereafter delivered to a new incarnation of the entity.
 *
 * This class is not intended for user extension other than for test purposes (e.g.
 * stub implementation). More methods may be added in the future and that may break
 * such implementations.
 */
@DoNotInherit
trait ClusterSharding extends Extension { javadslSelf: javadsl.ClusterSharding ⇒

  /**
   * Initialize sharding for the given `entity` factory settings.
   *
   * It will start a shard region or a proxy depending on if the settings require role and if this node has
   * such a role.
   *
   * @tparam M The type of message the entity accepts
   * @tparam E A possible envelope around the message the entity accepts
   */
  def init[M, E](entity: Entity[M, E]): ActorRef[E]

  /**
   * Create an `ActorRef`-like reference to a specific sharded entity.
   * Currently you have to correctly specify the type of messages the target can handle.
   *
   * Messages sent through this [[EntityRef]] will be wrapped in a [[ShardingEnvelope]] including the
   * here provided `entityId`.
   *
   * For in-depth documentation of its semantics, see [[EntityRef]].
   */
  def entityRefFor[M](typeKey: EntityTypeKey[M], entityId: String): EntityRef[M]

  /**
   * Actor for querying Cluster Sharding state
   */
  def shardState: ActorRef[ClusterShardingQuery]

  /**
   * The default is currently [[akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy]] with the
   * given `settings`. This could be changed in the future.
   */
  def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def asJava: javadsl.ClusterSharding = javadslSelf

}

object Entity {

  /**
   * Defines how the entity should be created. Used in [[ClusterSharding#init]]. More optional
   * settings can be defined using the `with` methods of the returned [[Entity]].
   *
   * Any [[Behavior]] can be used as a sharded entity actor, but the combination of sharding and persistent actors
   * is very common and therefore [[EventSourcedEntity]] is provided as a convenience for creating such
   * `EventSourcedBehavior`.
   *
   * @param typeKey A key that uniquely identifies the type of entity in this cluster
   * @param createBehavior Create the behavior for an entity given a [[EntityContext]] (includes entityId)
   * @tparam M The type of message the entity accepts
   */
  def apply[M](
    typeKey:        EntityTypeKey[M],
    createBehavior: EntityContext ⇒ Behavior[M]): Entity[M, ShardingEnvelope[M]] =
    new Entity(createBehavior, typeKey, None, Props.empty, None, None, None)
}

/**
 * Defines how the entity should be created. Used in [[ClusterSharding#init]].
 */
final class Entity[M, E] private[akka] (
  val createBehavior:     EntityContext ⇒ Behavior[M],
  val typeKey:            EntityTypeKey[M],
  val stopMessage:        Option[M],
  val entityProps:        Props,
  val settings:           Option[ClusterShardingSettings],
  val messageExtractor:   Option[ShardingMessageExtractor[E, M]],
  val allocationStrategy: Option[ShardAllocationStrategy]) {

  /**
   * [[akka.actor.typed.Props]] of the entity actors, such as dispatcher settings.
   */
  def withEntityProps(newEntityProps: Props): Entity[M, E] =
    copy(entityProps = newEntityProps)

  /**
   * Additional settings, typically loaded from configuration.
   */
  def withSettings(newSettings: ClusterShardingSettings): Entity[M, E] =
    copy(settings = Option(newSettings))

  /**
   * Message sent to an entity to tell it to stop, e.g. when rebalanced or passivated.
   * If this is not defined it will be stopped automatically.
   * It can be useful to define a custom stop message if the entity needs to perform
   * some asynchronous cleanup or interactions before stopping.
   */
  def withStopMessage(newStopMessage: M): Entity[M, E] =
    copy(stopMessage = Option(newStopMessage))

  /**
   *
   * If a `messageExtractor` is not specified the messages are sent to the entities by wrapping
   * them in [[ShardingEnvelope]] with the entityId of the recipient actor. That envelope
   * is used by the [[HashCodeMessageExtractor]] for extracting entityId and shardId. The number of
   * shards is then defined by `numberOfShards` in `ClusterShardingSettings`, which by default
   * is configured with `akka.cluster.sharding.number-of-shards`.
   */
  def withMessageExtractor[Envelope](newExtractor: ShardingMessageExtractor[Envelope, M]): Entity[M, Envelope] =
    new Entity(createBehavior, typeKey, stopMessage, entityProps, settings, Option(newExtractor), allocationStrategy)

  /**
   * Allocation strategy which decides on which nodes to allocate new shards,
   * [[ClusterSharding#defaultShardAllocationStrategy]] is used if this is not specified.
   */
  def withAllocationStrategy(newAllocationStrategy: ShardAllocationStrategy): Entity[M, E] =
    copy(allocationStrategy = Option(newAllocationStrategy))

  private def copy(
    createBehavior:     EntityContext ⇒ Behavior[M]     = createBehavior,
    typeKey:            EntityTypeKey[M]                = typeKey,
    stopMessage:        Option[M]                       = stopMessage,
    entityProps:        Props                           = entityProps,
    settings:           Option[ClusterShardingSettings] = settings,
    allocationStrategy: Option[ShardAllocationStrategy] = allocationStrategy
  ): Entity[M, E] = {
    new Entity(createBehavior, typeKey, stopMessage, entityProps, settings, messageExtractor, allocationStrategy)
  }

}

/**
 * Parameter to [[Entity.apply]]
 */
final class EntityContext(
  val entityId: String,
  val shard:    ActorRef[ClusterSharding.ShardCommand])

/** Allows starting a specific Sharded Entity by its entity identifier */
object StartEntity {

  /**
   * Returns [[ShardingEnvelope]] which can be sent via Cluster Sharding in order to wake up the
   * specified (by `entityId`) Sharded Entity, ''without'' delivering a real message to it.
   */
  def apply[M](entityId: String): ShardingEnvelope[M] = {
    // StartEntity isn't really of type M, but erased and StartEntity is only handled internally, not delivered to the entity
    new ShardingEnvelope[M](entityId, UntypedStartEntity(entityId).asInstanceOf[M])
  }
}

/**
 * The key of an entity type, the `name` must be unique.
 *
 * Not for user extension.
 */
@DoNotInherit trait EntityTypeKey[T] {

  /**
   * Name of the entity type.
   */
  def name: String

  /**
   * Constructs a [[PersistenceId]] from this `EntityTypeKey` and the given `entityId` by
   * concatenating them with `|` separator.
   *
   * The `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator in [[EntityTypeKey.withEntityIdSeparator]].
   */
  def persistenceIdFrom(entityId: String): PersistenceId

  /**
   * Specify a custom separator for compatibility with old naming conventions. The separator is used between the
   * `EntityTypeKey` and the `entityId` when constructing a `persistenceId` with [[EntityTypeKey.persistenceIdFrom]].
   *
   * The default `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator here.
   */
  def withEntityIdSeparator(separator: String): EntityTypeKey[T]
}

object EntityTypeKey {
  /**
   * Creates an `EntityTypeKey`. The `name` must be unique.
   */
  def apply[T](name: String)(implicit tTag: ClassTag[T]): EntityTypeKey[T] =
    EntityTypeKeyImpl(name, implicitly[ClassTag[T]].runtimeClass.getName)

}

/**
 * A reference to an sharded Entity, which allows `ActorRef`-like usage.
 *
 * An [[EntityRef]] is NOT an [[ActorRef]]–by design–in order to be explicit about the fact that the life-cycle
 * of a sharded Entity is very different than a plain Actors. Most notably, this is shown by features of Entities
 * such as re-balancing (an active Entity to a different node) or passivation. Both of which are aimed to be completely
 * transparent to users of such Entity. In other words, if this were to be a plain ActorRef, it would be possible to
 * apply DeathWatch to it, which in turn would then trigger when the sharded Actor stopped, breaking the illusion that
 * Entity refs are "always there". Please note that while not encouraged, it is possible to expose an Actor's `self`
 * [[ActorRef]] and watch it in case such notification is desired.
 * Not for user extension.
 */
@DoNotInherit trait EntityRef[M] extends RecipientRef[M] { this: InternalRecipientRef[M] ⇒

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   *
   * Example usage:
   * {{{
   * val target: EntityRef[String] = ...
   * target.tell("Hello")
   * }}}
   */
  def tell(msg: M): Unit

  /**
   * Send a message to the entity referenced by this EntityRef using *at-most-once*
   * messaging semantics.
   *
   * Example usage:
   * {{{
   * val target: EntityRef[String] = ...
   * target ! "Hello"
   * }}}
   */
  def !(msg: M): Unit = this.tell(msg)

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.actor.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
   *
   * Note that if you are inside of an actor you should prefer [[akka.actor.typed.scaladsl.ActorContext.ask]]
   * as that provides better safety.
   *
   * Example usage:
   * {{{
   * case class Request(msg: String, replyTo: ActorRef[Reply])
   * case class Reply(msg: String)
   *
   * implicit val timeout = Timeout(3.seconds)
   * val target: EntityRef[Request] = ...
   * val f: Future[Reply] = target.ask(Request("hello", _))
   * }}}
   *
   * Please note that an implicit [[akka.util.Timeout]] must be available to use this pattern.
   */
  def ask[U](f: ActorRef[U] ⇒ M)(implicit timeout: Timeout): Future[U]

  /**
   * Allows to "ask" the [[EntityRef]] for a reply.
   * See [[akka.actor.typed.scaladsl.AskPattern]] for a complete write-up of this pattern
   *
   * Note that if you are inside of an actor you should prefer [[akka.actor.typed.scaladsl.ActorContext.ask]]
   * as that provides better safety.
   *
   * Example usage:
   * {{{
   * case class Request(msg: String, replyTo: ActorRef[Reply])
   * case class Reply(msg: String)
   *
   * implicit val timeout = Timeout(3.seconds)
   * val target: EntityRef[Request] = ...
   * val f: Future[Reply] = target ? (Request("hello", _))
   * }}}
   *
   * Please note that an implicit [[akka.util.Timeout]] must be available to use this pattern.
   */
  def ?[U](message: ActorRef[U] ⇒ M)(implicit timeout: Timeout): Future[U] =
    this.ask(message)(timeout)

}

object ClusterShardingSetup {
  def apply[T <: Extension](createExtension: ActorSystem[_] ⇒ ClusterSharding): ClusterShardingSetup =
    new ClusterShardingSetup(new java.util.function.Function[ActorSystem[_], ClusterSharding] {
      override def apply(sys: ActorSystem[_]): ClusterSharding = createExtension(sys)
    }) // TODO can be simplified when compiled only with Scala >= 2.12

}

/**
 * Can be used in [[akka.actor.setup.ActorSystemSetup]] when starting the [[ActorSystem]]
 * to replace the default implementation of the [[ClusterSharding]] extension. Intended
 * for tests that need to replace extension with stub/mock implementations.
 */
final class ClusterShardingSetup(createExtension: java.util.function.Function[ActorSystem[_], ClusterSharding])
  extends ExtensionSetup[ClusterSharding](ClusterSharding, createExtension)
