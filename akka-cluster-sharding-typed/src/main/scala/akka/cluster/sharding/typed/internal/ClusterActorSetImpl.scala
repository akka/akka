/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.internal.ClusterActorSetImpl.EntityParent
import akka.cluster.sharding.typed.internal.ClusterActorSetImpl.KeepAlivePinger
import akka.cluster.sharding.typed.scaladsl.ClusterActorSet
import akka.cluster.sharding.typed.scaladsl.ClusterActorSetSettings
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityContext
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

@InternalApi
private[akka] object ClusterActorSetImpl {
  object EntityParent {
    sealed trait Command
    object Ping extends Command

    def apply(entityContext: EntityContext[Command], factory: EntityId => Behavior[_]): Behavior[Command] =
      Behaviors.setup { context =>
        def startChild() {
          context.watch(context.spawn(factory(entityContext.entityId), "entity"))
        }

        context.log.debug(s"Starting ClusterActorSet actor ${entityContext.entityId}")
        startChild()

        // FIXME need to think about graceful shutdown here, is it fine that we are just killed on rebalance?
        Behaviors
          .receiveMessage[Command] {
            case _ => Behaviors.same
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

  override def init(
      settings: ClusterActorSetSettings,
      numberOfEntities: Int,
      behaviorFactory: EntityId => Behavior[_]): Unit = {
    val identities = (0 to numberOfEntities).map(_.toString).toSet
    init(settings, identities, behaviorFactory)
  }

  override def init(
      settings: ClusterActorSetSettings,
      identities: Set[EntityId],
      behaviorFactory: EntityId => Behavior[_]): Unit = {
    val setId = typeKeyCounter.incrementAndGet()
    val entityTypeKey = EntityTypeKey[EntityParent.Command](s"cluster-actor-set-$setId")

    val entity = Entity(entityTypeKey)(ctx => EntityParent(ctx, behaviorFactory))

    val shardingRef = ClusterSharding(system).init(entity)

    system.systemActorOf(KeepAlivePinger(settings, identities, shardingRef), s"ClusterActorSetPinger-$setId")
  }
}
