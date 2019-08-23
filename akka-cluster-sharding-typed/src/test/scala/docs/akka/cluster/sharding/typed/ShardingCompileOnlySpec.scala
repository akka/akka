/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.concurrent.duration._
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.Entity
import com.github.ghik.silencer.silent
import docs.akka.persistence.typed.BlogPostExample
import docs.akka.persistence.typed.BlogPostExample.BlogCommand

@silent
object ShardingCompileOnlySpec {

  val system = ActorSystem(Behaviors.empty, "Sharding")

  object Basics {

    //#sharding-extension
    import akka.cluster.sharding.typed.ShardingEnvelope
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
    import akka.cluster.sharding.typed.scaladsl.EntityRef

    val sharding = ClusterSharding(system)
    //#sharding-extension

    //#counter
    //#counter-messages
    object Counter {
      //#counter
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Int]) extends Command
      //#counter-messages

      //#counter

      def apply(entityId: String): Behavior[Command] =
        counter(entityId, 0)

      private def counter(entityId: String, value: Int): Behavior[Command] =
        Behaviors.receiveMessage[Command] {
          case Increment =>
            counter(entityId, value + 1)
          case GetValue(replyTo) =>
            replyTo ! value
            Behaviors.same
        }

      //#counter-messages
    }
    //#counter-messages
    //#counter

    //#init
    val TypeKey = EntityTypeKey[Counter.Command]("Counter")

    val shardRegion: ActorRef[ShardingEnvelope[Counter.Command]] =
      sharding.init(Entity(typeKey = TypeKey, createBehavior = ctx => Counter(ctx.entityId)))
    //#init

    //#send
    // With an EntityRef
    val counterOne: EntityRef[Counter.Command] = sharding.entityRefFor(TypeKey, "counter-1")
    counterOne ! Counter.Increment

    // Entity id is specified via an `ShardingEnvelope`
    shardRegion ! ShardingEnvelope("counter-1", Counter.Increment)
    //#send

    import BlogPostExample.behavior

    //#persistence
    val BlogTypeKey = EntityTypeKey[BlogCommand]("BlogPost")

    ClusterSharding(system).init(Entity(typeKey = BlogTypeKey, createBehavior = ctx => behavior(ctx.entityId)))
    //#persistence

  }

  object CounterWithPassivate {
    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

    //#counter-passivate

    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Int]) extends Command
      private case object Idle extends Command
      case object GoodByeCounter extends Command

      def apply(shard: ActorRef[ClusterSharding.ShardCommand], entityId: String): Behavior[Command] = {
        Behaviors.setup { ctx =>
          def become(value: Int): Behavior[Command] =
            Behaviors.receiveMessage[Command] {
              case Increment =>
                become(value + 1)
              case GetValue(replyTo) =>
                replyTo ! value
                Behaviors.same
              case Idle =>
                // after receive timeout
                shard ! ClusterSharding.Passivate(ctx.self)
                Behaviors.same
              case GoodByeCounter =>
                // the stopMessage, used for rebalance and passivate
                Behaviors.stopped
            }

          ctx.setReceiveTimeout(30.seconds, Idle)
          become(0)
        }
      }
    }

    val TypeKey = EntityTypeKey[Counter.Command]("Counter")

    ClusterSharding(system).init(
      Entity(typeKey = TypeKey, createBehavior = ctx => Counter(ctx.shard, ctx.entityId))
        .withStopMessage(Counter.GoodByeCounter))
    //#counter-passivate

  }

  object CounterWithResponseToShardedActor {

    import akka.cluster.sharding.typed.scaladsl.ClusterSharding
    import akka.cluster.sharding.typed.scaladsl.EntityTypeKey

    //#sharded-response
    // a sharded actor that needs counter updates
    object CounterConsumer {
      sealed trait Command
      final case class NewCount(count: Long) extends Command
      val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("example-sharded-response")
    }

    // a sharded counter that sends responses to another sharded actor
    object Counter {
      trait Command
      case object Increment extends Command
      final case class GetValue(replyToEntityId: String) extends Command
      val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("example-sharded-counter")

      private def apply(): Behavior[Command] =
        Behaviors.setup { context =>
          counter(ClusterSharding(context.system), 0)
        }

      private def counter(sharding: ClusterSharding, value: Long): Behavior[Command] =
        Behaviors.receiveMessage {
          case Increment =>
            counter(sharding, value + 1)
          case GetValue(replyToEntityId) =>
            val replyToEntityRef = sharding.entityRefFor(CounterConsumer.TypeKey, replyToEntityId)
            replyToEntityRef ! CounterConsumer.NewCount(value)
            Behaviors.same
        }

    }
    //#sharded-response
  }

}
