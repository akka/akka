/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.util.Optional

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.ShardRegion.StartEntity
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.ShardingMessageExtractor
import akka.cluster.sharding.typed.scaladsl
import akka.cluster.sharding.typed.javadsl
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.japi.function
import scala.compat.java8.OptionConverters._

import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ShardedDaemonProcessImpl {

  object KeepAlivePinger {
    sealed trait Event
    case object Tick extends Event

    def apply[T](
        settings: ShardedDaemonProcessSettings,
        identities: Set[EntityId],
        shardingRef: ActorRef[ShardingEnvelope[T]]): Behavior[Event] =
      Behaviors.setup { context =>
        Behaviors.withTimers { timers =>
          def triggerStartAll(): Unit = {
            identities.foreach(id => shardingRef.toClassic ! StartEntity(id))
          }

          context.log.debug(
            s"Starting Sharded Daemon Set KeepAlivePinger with ping interval ${settings.keepAliveInterval}")
          timers.startTimerWithFixedDelay(Tick, settings.keepAliveInterval)
          triggerStartAll()

          Behaviors.receiveMessage {
            case Tick =>
              triggerStartAll()
              context.log.debug("Periodic ping sent to [{}] processes", identities.size)
              Behaviors.same
          }
        }
      }
  }

  def idAndNameToEntityId(id: Int, name: String): EntityId = s"$name-$id"

  def idFromEntityId(entityId: EntityId, name: String): Int =
    stringIdFromEntityId(entityId, name).toInt

  def stringIdFromEntityId(entityId: EntityId, name: String): String =
    entityId.drop(name.length + 1)

  final class MessageExtractor[T](name: String) extends ShardingMessageExtractor[ShardingEnvelope[T], T] {
    def entityId(message: ShardingEnvelope[T]): String = message match {
      case ShardingEnvelope(id, _) => id
    }

    def shardId(entityId: String): String = stringIdFromEntityId(entityId, name)

    def unwrapMessage(message: ShardingEnvelope[T]): T = message.message
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class ShardedDaemonProcessImpl(system: ActorSystem[_])
    extends javadsl.ShardedDaemonProcess
    with scaladsl.ShardedDaemonProcess {

  import ShardedDaemonProcessImpl._

  def init[T](name: String, numberOfInstances: Int, behaviorFactory: Int => Behavior[T])(
      implicit classTag: ClassTag[T]): Unit = {
    init(name, numberOfInstances, behaviorFactory, ShardedDaemonProcessSettings(system), None)(classTag)
  }

  def init[T](
      name: String,
      numberOfInstances: Int,
      behaviorFactory: Int => Behavior[T],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Option[T])(implicit classTag: ClassTag[T]): Unit = {

    val entityTypeKey = EntityTypeKey[T](s"sharded-daemon-process-$name")

    // One shard per actor identified by the numeric id encoded in the entity id
    val numberOfShards = numberOfInstances
    val entityIds = (0 to numberOfInstances).map(idAndNameToEntityId(_, name))

    val shardingSettings = {
      val settingsFromConfig =
        ClusterShardingSettings.fromConfig(
          // defaults in akka.cluster.sharding but allow overrides specifically for actor-set
          system.settings.config.getConfig("akka.cluster.sharded-daemon-process.sharding"))

      new ClusterShardingSettings(
        numberOfShards,
        settingsFromConfig.role,
        settingsFromConfig.dataCenter,
        false, // remember entities disabled
        "",
        "",
        Duration.Zero, // passivation disabled
        settingsFromConfig.shardRegionQueryTimeout,
        StateStoreModeDData,
        settingsFromConfig.tuningParameters,
        settingsFromConfig.coordinatorSingletonSettings)
    }

    val entity = Entity(entityTypeKey)(ctx => behaviorFactory(idFromEntityId(ctx.entityId, name)))
      .withSettings(shardingSettings)
      .withMessageExtractor(new MessageExtractor(name))

    val entityWithStop = stopMessage match {
      case Some(stop) => entity.withStopMessage(stop)
      case None       => entity
    }

    val shardingRef = ClusterSharding(system).init(entityWithStop)

    system.systemActorOf(
      KeepAlivePinger(settings, entityIds.toSet, shardingRef),
      s"shardedDaemonProcessKeepAlive-$name")
  }

  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: function.Function[Integer, Behavior[T]]): Unit =
    init(name, numberOfInstances, n => behaviorFactory(n))(ClassTag(messageClass))

  def init[T](
      messageClass: Class[T],
      name: String,
      numberOfInstances: Int,
      behaviorFactory: function.Function[Integer, Behavior[T]],
      settings: ShardedDaemonProcessSettings,
      stopMessage: Optional[T]): Unit =
    init(name, numberOfInstances, n => behaviorFactory(n), settings, stopMessage.asScala)(ClassTag(messageClass))
}
