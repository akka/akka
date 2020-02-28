/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.DeadLetterSuppression
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ClusterShardingSettings.StateStoreModeDData
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.internal.ClusterActorSetImpl.EntityParent
import akka.cluster.sharding.typed.internal.ClusterActorSetImpl.KeepAlivePinger
import akka.cluster.sharding.typed.scaladsl.ClusterActorSet
import akka.cluster.sharding.typed.scaladsl.ClusterActorSetSettings
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ClusterActorSetImpl {
  object EntityParent {
    sealed trait Command
    case object Ping extends Command with DeadLetterSuppression
    case object Stop extends Command

    def apply[T](
        entityContext: EntityContext[Command],
        factory: EntityId => Behavior[T],
        childStopMessage: T): Behavior[Command] =
      Behaviors.setup { context =>
        var child: Option[ActorRef[T]] = None

        def startChild(): Unit = {
          val startedChild = context.spawn(factory(entityContext.entityId), "entity")
          context.watch(startedChild)
          child = Some(startedChild)
        }

        context.log.debug(s"Starting ClusterActorSet actor ${entityContext.entityId}")
        startChild()

        Behaviors
          .receiveMessage[Command] {
            case Stop =>
              child match {
                case Some(ref) =>
                  ref ! childStopMessage
                  waitingForChildToStop()
                case None =>
                  Behaviors.stopped
              }

            case Ping =>
              Behaviors.same
          }
          .receiveSignal {
            case (_, Terminated(_)) =>
              // if the child stopped manually, but we are not being re-located to another node,
              // we can fast-restart it instead of waiting for a ping
              context.log.debug(s"ClusterActorSet actor ${entityContext.entityId} stopped, starting again")
              startChild()
              Behaviors.same
          }
      }

    private def waitingForChildToStop(): Behavior[Command] = Behaviors.setup { context =>
      // FIXME timeout and force termination if child doesn't stop?
      Behaviors.receiveSignal {
        case (_, Terminated(_)) =>
          // if the child stopped manually, but we are not being re-located to another node,
          // we can fast-restart it instead of waiting for a ping
          context.log.debug(s"ClusterActorSet actor stopped")
          Behaviors.stopped
      }
    }
  }

  object KeepAlivePinger {
    sealed trait Event
    case object Tick extends Event

    def apply(
        settings: ClusterActorSetSettings,
        identities: Set[EntityId],
        shardingRef: ActorRef[ShardingEnvelope[EntityParent.Command]]): Behavior[Event] =
      Behaviors.setup { context =>
        Behaviors.withTimers { timers =>
          context.log.debug(
            s"Starting ClusterActorSet KeepAlivePinger with ping interval ${settings.keepAliveInterval}")
          // FIXME should we delay initial tick with random fraction of keepAliveInterval to avoid thundering herd?
          timers.startTimerWithFixedDelay(Tick, settings.keepAliveInterval)
          Behaviors.receiveMessage {
            case Tick =>
              identities.foreach(id => shardingRef ! ShardingEnvelope(id, EntityParent.Ping))
              Behaviors.same
          }
        }
      }
  }

}
@InternalApi
private[akka] class ClusterActorSetImpl(system: ActorSystem[_]) extends ClusterActorSet {

  private val typeKeyCounter = new AtomicInteger(0)

  override def init[T](numberOfEntities: Int, behaviorFactory: EntityId => Behavior[T], stopMessage: T): Unit =
    init(ClusterActorSetSettings(system), numberOfEntities, behaviorFactory, stopMessage)

  override def init[T](
      settings: ClusterActorSetSettings,
      numberOfEntities: Int,
      behaviorFactory: EntityId => Behavior[T],
      stopMessage: T): Unit = {
    val identities = (0 to numberOfEntities).map(_.toString).toSet
    init(settings, identities, behaviorFactory, stopMessage)
  }

  override def init[T](identities: Set[EntityId], behaviorFactory: EntityId => Behavior[T], stopMessage: T): Unit =
    init(ClusterActorSetSettings(system), identities, behaviorFactory, stopMessage)

  override def init[T](
      settings: ClusterActorSetSettings,
      identities: Set[EntityId],
      behaviorFactory: EntityId => Behavior[T],
      stopMessage: T): Unit = {
    val setId = typeKeyCounter.incrementAndGet()
    val entityTypeKey = EntityTypeKey[EntityParent.Command](s"cluster-actor-set-$setId")

    // Since we know up front exactly what entity ids will exist and the number will be low 1:1 is fine as it will
    // balance the set as good as possible across the cluster
    val numberOfShards = identities.size

    val shardingSettings = {
      val settingsFromConfig =
        ClusterShardingSettings.fromConfig(
          // defaults in akka.cluster.sharding but allow overrides specifically for actor-set
          system.settings.config.getConfig("akka.cluster.actor-set.sharding"))

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

    val entity =
      Entity(entityTypeKey)(ctx => EntityParent(ctx, behaviorFactory, stopMessage))
        .withSettings(shardingSettings)
        .withStopMessage(EntityParent.Stop)

    val shardingRef = ClusterSharding(system).init(entity)

    system.systemActorOf(KeepAlivePinger(settings, identities, shardingRef), s"clusterActorSetPinger-$setId")
  }
}
