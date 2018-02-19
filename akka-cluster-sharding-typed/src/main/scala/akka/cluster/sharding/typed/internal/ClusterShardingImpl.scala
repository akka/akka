/*
 * Copyright (C) 2017-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.cluster.sharding.typed
package internal

import java.util.Optional
import java.util.concurrent.{ CompletionStage, ConcurrentHashMap }

import scala.compat.java8.OptionConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import akka.actor.{ InternalActorRef, Scheduler }
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.Props
import akka.actor.typed.internal.adapter.ActorRefAdapter
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.{ StartEntity ⇒ UntypedStartEntity }
import akka.cluster.sharding.typed.javadsl.EntityIdToBehavior
import akka.cluster.typed.Cluster
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.pattern.AskTimeoutException
import akka.pattern.PromiseActorRef
import akka.util.Timeout
import akka.japi.function.{ Function ⇒ JFunction }

/**
 * INTERNAL API
 * Extracts entityId and unwraps ShardingEnvelope and StartEntity messages.
 * Other messages are delegated to the given `ShardingMessageExtractor`.
 */
@InternalApi private[akka] class ExtractorAdapter[E, A](delegate: ShardingMessageExtractor[E, A])
  extends ShardingMessageExtractor[Any, A] {
  override def entityId(message: Any): String = {
    message match {
      case ShardingEnvelope(entityId, _) ⇒ entityId //also covers UntypedStartEntity in ShardingEnvelope
      case UntypedStartEntity(entityId)  ⇒ entityId
      case msg: E @unchecked             ⇒ delegate.entityId(msg)
    }
  }

  override def shardId(entityId: String): String = delegate.shardId(entityId)

  override def unwrapMessage(message: Any): A = {
    message match {
      case ShardingEnvelope(_, msg: A @unchecked) ⇒
        //also covers UntypedStartEntity in ShardingEnvelope
        msg
      case msg: UntypedStartEntity ⇒
        // not really of type A, but erased and StartEntity is only handled internally, not delivered to the entity
        msg.asInstanceOf[A]
      case msg: E @unchecked ⇒
        delegate.unwrapMessage(msg)
    }
  }

  override def handOffStopMessage: A = delegate.handOffStopMessage

  override def toString: String = delegate.toString
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final case class EntityTypeKeyImpl[T](name: String, messageClassName: String)
  extends javadsl.EntityTypeKey[T] with scaladsl.EntityTypeKey[T] {
  override def toString: String = s"EntityTypeKey[$messageClassName]($name)"
}

/** INTERNAL API */
@InternalApi private[akka] final class ClusterShardingImpl(system: ActorSystem[_])
  extends javadsl.ClusterSharding with scaladsl.ClusterSharding {

  import akka.actor.typed.scaladsl.adapter._

  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted untyped actor systems can be used for cluster features")

  private val cluster = Cluster(system)
  private val untypedSystem = system.toUntyped
  private val untypedSharding = akka.cluster.sharding.ClusterSharding(untypedSystem)
  private val log: LoggingAdapter = Logging(untypedSystem, classOf[scaladsl.ClusterSharding])

  // typeKey.name to messageClassName
  private val regions: ConcurrentHashMap[String, String] = new ConcurrentHashMap
  private val proxies: ConcurrentHashMap[String, String] = new ConcurrentHashMap

  override def spawn[A](
    behavior:           String ⇒ Behavior[A],
    entityProps:        Props,
    typeKey:            scaladsl.EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]] = {
    val extractor = new HashCodeMessageExtractor[A](maxNumberOfShards, handOffStopMessage)
    spawnWithMessageExtractor(behavior, entityProps, typeKey, settings, extractor, Some(defaultShardAllocationStrategy(settings)))
  }

  override def spawn[A](
    behavior:           EntityIdToBehavior[A],
    entityProps:        Props,
    typeKey:            javadsl.EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    maxNumberOfShards:  Int,
    handOffStopMessage: A): ActorRef[ShardingEnvelope[A]] = {
    val extractor = new HashCodeMessageExtractor[A](maxNumberOfShards, handOffStopMessage)
    spawnWithMessageExtractor(behavior, entityProps, typeKey, settings, extractor,
      Optional.of(defaultShardAllocationStrategy(settings)))
  }

  override def spawnWithMessageExtractor[E, A](
    behavior:           String ⇒ Behavior[A],
    entityProps:        Props,
    typeKey:            scaladsl.EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    extractor:          ShardingMessageExtractor[E, A],
    allocationStrategy: Option[ShardAllocationStrategy]): ActorRef[E] = {

    val extractorAdapter = new ExtractorAdapter(extractor)
    val extractEntityId: ShardRegion.ExtractEntityId = {
      // TODO is it possible to avoid the double evaluation of entityId
      case message if extractorAdapter.entityId(message) != null ⇒
        (extractorAdapter.entityId(message), extractorAdapter.unwrapMessage(message))
    }
    val extractShardId: ShardRegion.ExtractShardId = { message ⇒
      extractorAdapter.entityId(message) match {
        case null ⇒ null
        case eid  ⇒ extractorAdapter.shardId(eid)
      }
    }

    val ref =
      if (settings.shouldHostShard(cluster)) {
        log.info("Starting Shard Region [{}]...", typeKey.name)

        val untypedEntityPropsFactory: String ⇒ akka.actor.Props = { entityId ⇒
          behavior(entityId) match {
            case u: UntypedBehavior[_] ⇒ u.untypedProps // PersistentBehavior
            case b                     ⇒ PropsAdapter(b, entityProps)
          }
        }

        untypedSharding.internalStart(
          typeKey.name,
          untypedEntityPropsFactory,
          ClusterShardingSettings.toUntypedSettings(settings),
          extractEntityId,
          extractShardId,
          allocationStrategy.getOrElse(defaultShardAllocationStrategy(settings)),
          extractor.handOffStopMessage)
      } else {
        log.info("Starting Shard Region Proxy [{}] (no actors will be hosted on this node) " +
          "for role [{}] and dataCenter [{}] ...", typeKey.name, settings.role, settings.dataCenter)

        untypedSharding.startProxy(
          typeKey.name,
          settings.role,
          dataCenter = settings.dataCenter,
          extractEntityId,
          extractShardId)
      }

    val messageClassName = typeKey.asInstanceOf[EntityTypeKeyImpl[A]].messageClassName

    val typeNames = if (settings.shouldHostShard(cluster)) regions else proxies

    typeNames.putIfAbsent(typeKey.name, messageClassName) match {
      case spawnedMessageClassName: String if messageClassName != spawnedMessageClassName ⇒
        throw new IllegalArgumentException(s"[${typeKey.name}] already spawned for [$spawnedMessageClassName]")
      case _ ⇒ ()
    }

    ActorRefAdapter(ref)
  }

  override def spawnWithMessageExtractor[E, A](
    behavior:           EntityIdToBehavior[A],
    entityProps:        Props,
    typeKey:            javadsl.EntityTypeKey[A],
    settings:           ClusterShardingSettings,
    extractor:          ShardingMessageExtractor[E, A],
    allocationStrategy: Optional[ShardAllocationStrategy]): ActorRef[E] = {
    spawnWithMessageExtractor(entityId ⇒ behavior.apply(entityId), entityProps, typeKey.asScala,
      settings, extractor, allocationStrategy.asScala)
  }

  override def entityRefFor[A](typeKey: scaladsl.EntityTypeKey[A], entityId: String): scaladsl.EntityRef[A] = {
    new EntityRefImpl[A](untypedSharding.shardRegion(typeKey.name), entityId)
  }

  override def entityRefFor[A](typeKey: javadsl.EntityTypeKey[A], entityId: String): javadsl.EntityRef[A] = {
    new EntityRefImpl[A](untypedSharding.shardRegion(typeKey.name), entityId)
  }

  override def defaultShardAllocationStrategy(settings: ClusterShardingSettings): ShardAllocationStrategy = {
    val threshold = settings.tuningParameters.leastShardAllocationRebalanceThreshold
    val maxSimultaneousRebalance = settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance
    new LeastShardAllocationStrategy(threshold, maxSimultaneousRebalance)
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class EntityRefImpl[A](shardRegion: akka.actor.ActorRef, entityId: String)
  extends javadsl.EntityRef[A] with scaladsl.EntityRef[A] {

  override def tell(msg: A): Unit =
    shardRegion ! ShardingEnvelope(entityId, msg)

  override def ask[U](message: (ActorRef[U]) ⇒ A)(implicit timeout: Timeout, scheduler: Scheduler): Future[U] = {
    val replyTo = new EntityPromiseRef[U](shardRegion.asInstanceOf[InternalActorRef], timeout)
    val m = message(replyTo.ref)
    if (replyTo.promiseRef ne null) replyTo.promiseRef.messageClassName = m.getClass.getName
    shardRegion ! ShardingEnvelope(entityId, m)
    replyTo.future
  }

  def ask[U](message: JFunction[ActorRef[U], A], timeout: Timeout, scheduler: Scheduler): CompletionStage[U] =
    ask[U](replyTo ⇒ message.apply(replyTo))(timeout, scheduler).toJava

  /** Similar to [[akka.actor.typed.scaladsl.AskPattern.PromiseRef]] but for an `EntityRef` target. */
  @InternalApi
  private final class EntityPromiseRef[U](untyped: InternalActorRef, timeout: Timeout) {
    import akka.actor.typed.internal.{ adapter ⇒ adapt }

    // Note: _promiseRef mustn't have a type pattern, since it can be null
    private[this] val (_ref: ActorRef[U], _future: Future[U], _promiseRef) =
      if (untyped.isTerminated)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new AskTimeoutException(s"Recipient[$untyped] had already been terminated.")),
          null)
      else if (timeout.duration.length <= 0)
        (
          adapt.ActorRefAdapter[U](untyped.provider.deadLetters),
          Future.failed[U](new IllegalArgumentException(s"Timeout length must be positive, question not sent to [$untyped]")),
          null
        )
      else {
        val a = PromiseActorRef(untyped.provider, timeout, untyped, "unknown")
        val b = adapt.ActorRefAdapter[U](a)
        (b, a.result.future.asInstanceOf[Future[U]], a)
      }

    val ref: ActorRef[U] = _ref
    val future: Future[U] = _future
    val promiseRef: PromiseActorRef = _promiseRef
  }

}
