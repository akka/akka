/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed
package internal

import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

import scala.compat.java8.FutureConverters._

import akka.util.JavaDurationConverters._
import scala.concurrent.Future

import akka.actor.ActorRefProvider
import akka.actor.ExtendedActorSystem
import akka.actor.InternalActorRef
import akka.actor.typed.TypedActorContext
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.internal.InternalRecipientRef
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.internal.PoisonPillInterceptor
import akka.actor.typed.internal.adapter.ActorRefAdapter
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.{ StartEntity => ClassicStartEntity }
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.typed.Cluster
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.japi.function.{ Function => JFunction }
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import akka.util.ByteString
import akka.util.Timeout

/**
 * INTERNAL API
 * Extracts entityId and unwraps ShardingEnvelope and StartEntity messages.
 * Other messages are delegated to the given `ShardingMessageExtractor`.
 */
@InternalApi private[akka] class ExtractorAdapter[E, M](delegate: ShardingMessageExtractor[E, M])
    extends ShardingMessageExtractor[Any, M] {
  override def entityId(message: Any): String = {
    message match {
      case ShardingEnvelope(entityId, _) => entityId //also covers ClassicStartEntity in ShardingEnvelope
      case ClassicStartEntity(entityId)  => entityId
      case msg: E @unchecked             => delegate.entityId(msg)
    }
  }

  override def shardId(entityId: String): String = delegate.shardId(entityId)

  override def unwrapMessage(message: Any): M = {
    message match {
      case ShardingEnvelope(_, msg: M @unchecked) =>
        //also covers ClassicStartEntity in ShardingEnvelope
        msg
      case msg: ClassicStartEntity =>
        // not really of type M, but erased and StartEntity is only handled internally, not delivered to the entity
        msg.asInstanceOf[M]
      case msg: E @unchecked =>
        delegate.unwrapMessage(msg)
    }
  }

  override def toString: String = delegate.toString
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class EntityTypeKeyImpl[T](name: String, messageClassName: String)
    extends javadsl.EntityTypeKey[T]
    with scaladsl.EntityTypeKey[T] {

  override def toString: String = s"EntityTypeKey[$messageClassName]($name)"
}

/** INTERNAL API */
@InternalApi private[akka] final class ClusterShardingImpl(system: ActorSystem[_])
    extends javadsl.ClusterSharding
    with scaladsl.ClusterSharding {

  import akka.actor.typed.scaladsl.adapter._

  require(
    system.isInstanceOf[ActorSystemAdapter[_]],
    "only adapted classic actor systems can be used for cluster features")

  private val cluster = Cluster(system)
  private val classicSystem: ExtendedActorSystem = system.toClassic.asInstanceOf[ExtendedActorSystem]
  private val classicSharding = akka.cluster.sharding.ClusterSharding(classicSystem)
  private val log: LoggingAdapter = Logging(classicSystem, classOf[scaladsl.ClusterSharding])

  // typeKey.name to messageClassName
  private val regions: ConcurrentHashMap[String, String] = new ConcurrentHashMap
  private val proxies: ConcurrentHashMap[String, String] = new ConcurrentHashMap
  private val shardCommandActors: ConcurrentHashMap[String, ActorRef[scaladsl.ClusterSharding.ShardCommand]] =
    new ConcurrentHashMap

  // scaladsl impl
  override def init[M, E](entity: scaladsl.Entity[M, E]): ActorRef[E] = {
    val settings = entity.settings match {
      case None    => ClusterShardingSettings(system)
      case Some(s) => s
    }

    val extractor = (entity.messageExtractor match {
      case None    => new HashCodeMessageExtractor[M](settings.numberOfShards)
      case Some(e) => e
    }).asInstanceOf[ShardingMessageExtractor[E, M]]

    val settingsWithRole = entity.role.fold(settings)(settings.withRole)
    val settingsWithDataCenter = entity.dataCenter.fold(settingsWithRole)(settingsWithRole.withDataCenter)

    internalInit(
      entity.createBehavior,
      entity.entityProps,
      entity.typeKey,
      entity.stopMessage,
      settingsWithDataCenter,
      extractor,
      entity.allocationStrategy)
  }

  // javadsl impl
  override def init[M, E](entity: javadsl.Entity[M, E]): ActorRef[E] = {
    import scala.compat.java8.OptionConverters._
    init(
      new scaladsl.Entity(
        createBehavior = (ctx: EntityContext[M]) =>
          entity.createBehavior(new javadsl.EntityContext[M](entity.typeKey, ctx.entityId, ctx.shard)),
        typeKey = entity.typeKey.asScala,
        stopMessage = entity.stopMessage.asScala,
        entityProps = entity.entityProps,
        settings = entity.settings.asScala,
        messageExtractor = entity.messageExtractor.asScala,
        allocationStrategy = entity.allocationStrategy.asScala,
        role = entity.role.asScala,
        dataCenter = entity.dataCenter.asScala))
  }

  private def internalInit[M, E](
      behavior: EntityContext[M] => Behavior[M],
      entityProps: Props,
      typeKey: scaladsl.EntityTypeKey[M],
      stopMessage: Option[M],
      settings: ClusterShardingSettings,
      extractor: ShardingMessageExtractor[E, M],
      allocationStrategy: Option[ShardAllocationStrategy]): ActorRef[E] = {

    val extractorAdapter = new ExtractorAdapter(extractor)
    val extractEntityId: ShardRegion.ExtractEntityId = {
      // TODO is it possible to avoid the double evaluation of entityId
      case message if extractorAdapter.entityId(message) != null =>
        (extractorAdapter.entityId(message), extractorAdapter.unwrapMessage(message))
    }
    val extractShardId: ShardRegion.ExtractShardId = { message =>
      extractorAdapter.entityId(message) match {
        case null => null
        case eid  => extractorAdapter.shardId(eid)
      }
    }

    val ref =
      if (settings.shouldHostShard(cluster)) {
        log.info("Starting Shard Region [{}]...", typeKey.name)

        val shardCommandDelegator: ActorRef[scaladsl.ClusterSharding.ShardCommand] =
          shardCommandActors.computeIfAbsent(
            typeKey.name,
            new java.util.function.Function[String, ActorRef[scaladsl.ClusterSharding.ShardCommand]] {
              override def apply(t: String): ActorRef[scaladsl.ClusterSharding.ShardCommand] = {
                // using classic.systemActorOf to avoid the Future[ActorRef]
                system.toClassic
                  .asInstanceOf[ExtendedActorSystem]
                  .systemActorOf(
                    PropsAdapter(ShardCommandActor.behavior(stopMessage.getOrElse(PoisonPill))),
                    URLEncoder.encode(typeKey.name, ByteString.UTF_8) + "ShardCommandDelegator")
              }
            })

        def poisonPillInterceptor(behv: Behavior[M]): Behavior[M] = {
          stopMessage match {
            case Some(_) => behv
            case None    => Behaviors.intercept(() => new PoisonPillInterceptor[M])(behv)
          }
        }

        val classicEntityPropsFactory: String => akka.actor.Props = { entityId =>
          val behv = behavior(new EntityContext(typeKey, entityId, shardCommandDelegator))
          PropsAdapter(poisonPillInterceptor(behv), entityProps)
        }
        classicSharding.internalStart(
          typeKey.name,
          classicEntityPropsFactory,
          ClusterShardingSettings.toClassicSettings(settings),
          extractEntityId,
          extractShardId,
          allocationStrategy.getOrElse(defaultShardAllocationStrategy(settings)),
          stopMessage.getOrElse(PoisonPill))
      } else {
        log.info(
          "Starting Shard Region Proxy [{}] (no actors will be hosted on this node) " +
          "for role [{}] and dataCenter [{}] ...",
          typeKey.name,
          settings.role,
          settings.dataCenter)

        classicSharding.startProxy(
          typeKey.name,
          settings.role,
          dataCenter = settings.dataCenter,
          extractEntityId,
          extractShardId)
      }

    val messageClassName = typeKey.asInstanceOf[EntityTypeKeyImpl[M]].messageClassName

    val typeNames = if (settings.shouldHostShard(cluster)) regions else proxies

    typeNames.putIfAbsent(typeKey.name, messageClassName) match {
      case spawnedMessageClassName: String if messageClassName != spawnedMessageClassName =>
        throw new IllegalArgumentException(s"[${typeKey.name}] already initialized for [$spawnedMessageClassName]")
      case _ => ()
    }

    ActorRefAdapter(ref)
  }

  override def entityRefFor[M](typeKey: scaladsl.EntityTypeKey[M], entityId: String): scaladsl.EntityRef[M] = {
    new EntityRefImpl[M](
      classicSharding.shardRegion(typeKey.name),
      entityId,
      typeKey.asInstanceOf[EntityTypeKeyImpl[M]])
  }

  override def entityRefFor[M](
      typeKey: scaladsl.EntityTypeKey[M],
      entityId: String,
      dataCenter: DataCenter): scaladsl.EntityRef[M] = {
    if (dataCenter == cluster.selfMember.dataCenter)
      entityRefFor(typeKey, entityId)
    else
      new EntityRefImpl[M](
        classicSharding.shardRegionProxy(typeKey.name, dataCenter),
        entityId,
        typeKey.asInstanceOf[EntityTypeKeyImpl[M]])
  }

  override def entityRefFor[M](typeKey: javadsl.EntityTypeKey[M], entityId: String): javadsl.EntityRef[M] = {
    new EntityRefImpl[M](
      classicSharding.shardRegion(typeKey.name),
      entityId,
      typeKey.asInstanceOf[EntityTypeKeyImpl[M]])
  }

  override def entityRefFor[M](
      typeKey: javadsl.EntityTypeKey[M],
      entityId: String,
      dataCenter: String): javadsl.EntityRef[M] = {
    if (dataCenter == cluster.selfMember.dataCenter)
      entityRefFor(typeKey, entityId)
    else
      new EntityRefImpl[M](
        classicSharding.shardRegionProxy(typeKey.name, dataCenter),
        entityId,
        typeKey.asInstanceOf[EntityTypeKeyImpl[M]])
  }

  override def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy = {
    val threshold = settings.tuningParameters.leastShardAllocationRebalanceThreshold
    val maxSimultaneousRebalance = settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance
    new LeastShardAllocationStrategy(threshold, maxSimultaneousRebalance)
  }

  override lazy val shardState: ActorRef[ClusterShardingQuery] = {
    import akka.actor.typed.scaladsl.adapter._
    val behavior = ShardingState.behavior(classicSharding)
    classicSystem.systemActorOf(PropsAdapter(behavior), "typedShardState")
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class EntityRefImpl[M](
    shardRegion: akka.actor.ActorRef,
    entityId: String,
    typeKey: EntityTypeKeyImpl[M])
    extends javadsl.EntityRef[M]
    with scaladsl.EntityRef[M]
    with InternalRecipientRef[M] {

  override def tell(msg: M): Unit =
    shardRegion ! ShardingEnvelope(entityId, msg)

  override def ask[U](message: ActorRef[U] => M)(implicit timeout: Timeout): Future[U] = {
    val replyTo = new EntityPromiseRef[U](shardRegion.asInstanceOf[InternalActorRef], timeout)
    val m = message(replyTo.ref)
    if (replyTo.promiseRef ne null) replyTo.promiseRef.messageClassName = m.getClass.getName
    shardRegion ! ShardingEnvelope(entityId, m)
    replyTo.future
  }

  def ask[U](message: JFunction[ActorRef[U], M], timeout: Duration): CompletionStage[U] =
    ask[U](replyTo => message.apply(replyTo))(timeout.asScala).toJava

  /** Similar to [[akka.actor.typed.scaladsl.AskPattern.PromiseRef]] but for an `EntityRef` target. */
  @InternalApi
  private final class EntityPromiseRef[U](classic: InternalActorRef, timeout: Timeout) {
    import akka.actor.typed.internal.{ adapter => adapt }

    // Note: _promiseRef mustn't have a type pattern, since it can be null
    private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
      if (classic.isTerminated)
        (
          adapt.ActorRefAdapter[U](classic.provider.deadLetters),
          Future.failed[U](
            new AskTimeoutException(s"Recipient shard region of [${EntityRefImpl.this}] had already been terminated.")),
          null)
      else if (timeout.duration.length <= 0)
        (
          adapt.ActorRefAdapter[U](classic.provider.deadLetters),
          Future.failed[U](
            new IllegalArgumentException(
              s"Timeout length must be positive, question not sent to [${EntityRefImpl.this}]")),
          null)
      else {
        // note that the real messageClassName will be set afterwards, replyTo pattern
        val a =
          PromiseActorRef(classic.provider, timeout, targetName = EntityRefImpl.this, messageClassName = "unknown")
        val b = adapt.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }

    val ref: ActorRef[U] = _ref
    val future: Future[U] = _future
    val promiseRef: PromiseActorRef = _promiseRef
  }

  // impl InternalRecipientRef
  override def provider: ActorRefProvider = {
    import akka.actor.typed.scaladsl.adapter._
    shardRegion.toTyped.asInstanceOf[InternalRecipientRef[_]].provider
  }

  // impl InternalRecipientRef
  def isTerminated: Boolean = {
    import akka.actor.typed.scaladsl.adapter._
    shardRegion.toTyped.asInstanceOf[InternalRecipientRef[_]].isTerminated
  }

  override def toString: String = s"EntityRef($typeKey, $entityId)"

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ShardCommandActor {
  import akka.actor.typed.scaladsl.adapter._
  import akka.cluster.sharding.ShardRegion.{ Passivate => ClassicPassivate }

  def behavior(stopMessage: Any): Behavior[scaladsl.ClusterSharding.ShardCommand] = {
    def sendClassicPassivate(entity: ActorRef[_], ctx: TypedActorContext[_]): Unit = {
      val pathToShard = entity.toClassic.path.elements.take(4).mkString("/")
      ctx.asScala.system.toClassic.actorSelection(pathToShard).tell(ClassicPassivate(stopMessage), entity.toClassic)
    }

    Behaviors.receive { (ctx, msg) =>
      msg match {
        case scaladsl.ClusterSharding.Passivate(entity) =>
          sendClassicPassivate(entity, ctx)
          Behaviors.same
        case javadsl.ClusterSharding.Passivate(entity) =>
          sendClassicPassivate(entity, ctx)
          Behaviors.same
        case _ =>
          Behaviors.unhandled
      }
    }
  }

}
