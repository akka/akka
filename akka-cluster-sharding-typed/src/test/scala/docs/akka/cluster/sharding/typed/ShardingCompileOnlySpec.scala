/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.concurrent.duration._

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.Entity
import docs.akka.persistence.typed.BlogPostExample
import docs.akka.persistence.typed.BlogPostExample.{ BlogCommand, PassivatePost }

object ShardingCompileOnlySpec {

  val system = ActorSystem(Behaviors.empty, "Sharding")

  //#sharding-extension
  import akka.cluster.sharding.typed.ShardingEnvelope
  import akka.cluster.sharding.typed.scaladsl.ClusterSharding
  import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
  import akka.cluster.sharding.typed.scaladsl.EntityRef

  val sharding = ClusterSharding(system)
  //#sharding-extension

  //#counter-messages
  trait CounterCommand
  case object Increment extends CounterCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends CounterCommand
  case object GoodByeCounter extends CounterCommand
  //#counter-messages

  //#counter

  def counter(entityId: String, value: Int): Behavior[CounterCommand] =
    Behaviors.receiveMessage[CounterCommand] {
      case Increment ⇒
        counter(entityId, value + 1)
      case GetValue(replyTo) ⇒
        replyTo ! value
        Behaviors.same
      case GoodByeCounter ⇒
        Behaviors.stopped
    }
  //#counter

  //#start
  val TypeKey = EntityTypeKey[CounterCommand]("Counter")

  val shardRegion: ActorRef[ShardingEnvelope[CounterCommand]] = sharding.start(Entity(
    typeKey = TypeKey,
    createBehavior = ctx ⇒ counter(ctx.entityId, 0),
    stopMessage = GoodByeCounter))
  //#start

  //#send
  // With an EntityRef
  val counterOne: EntityRef[CounterCommand] = sharding.entityRefFor(TypeKey, "counter-1")
  counterOne ! Increment

  // Entity id is specified via an `ShardingEnvelope`
  shardRegion ! ShardingEnvelope("counter-1", Increment)
  //#send

  import BlogPostExample.behavior
  //#persistence
  val BlogTypeKey = EntityTypeKey[BlogCommand]("BlogPost")

  ClusterSharding(system).start(Entity(
    typeKey = BlogTypeKey,
    createBehavior = ctx ⇒ behavior(ctx.entityId),
    stopMessage = PassivatePost))
  //#persistence

  //#counter-passivate

  case object Idle extends CounterCommand

  def counter2(shard: ActorRef[ClusterSharding.ShardCommand], entityId: String): Behavior[CounterCommand] = {
    Behaviors.setup { ctx ⇒

      def become(value: Int): Behavior[CounterCommand] =
        Behaviors.receiveMessage[CounterCommand] {
          case Increment ⇒
            become(value + 1)
          case GetValue(replyTo) ⇒
            replyTo ! value
            Behaviors.same
          case Idle ⇒
            // after receive timeout
            shard ! ClusterSharding.Passivate(ctx.self)
            Behaviors.same
          case GoodByeCounter ⇒
            // the stopMessage, used for rebalance and passivate
            Behaviors.stopped
        }

      ctx.setReceiveTimeout(30.seconds, Idle)
      become(0)
    }
  }

  sharding.start(Entity(
    typeKey = TypeKey,
    createBehavior = ctx ⇒ counter2(ctx.shard, ctx.entityId),
    stopMessage = GoodByeCounter))
  //#counter-passivate

}
