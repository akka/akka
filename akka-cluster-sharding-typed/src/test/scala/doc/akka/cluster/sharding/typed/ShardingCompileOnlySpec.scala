package doc.akka.cluster.sharding.typed

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{ ClusterSingleton, ClusterSingletonSettings }
import docs.akka.persistence.typed.InDepthPersistentBehaviorSpec
import docs.akka.persistence.typed.InDepthPersistentBehaviorSpec.{ BlogCommand, PassivatePost }

object ShardingCompileOnlySpec {

  val system = ActorSystem(Behaviors.empty, "Sharding")

  //#sharding-extension
  import akka.cluster.sharding.typed._
  val sharding = ClusterSharding(system)
  //#sharding-extension

  //#counter
  trait CounterCommand
  case object Increment extends CounterCommand
  final case class GetValue(replyTo: ActorRef[Int]) extends CounterCommand
  case object GoodByeCounter extends CounterCommand

  def counter(value: Int): Behavior[CounterCommand] = Behaviors.immutable[CounterCommand] {
    case (ctx, Increment) ⇒
      counter(value + 1)
    case (ctx, GetValue(replyTo)) ⇒
      replyTo ! value
      Behaviors.same
  }
  //#counter

  //#spawn
  val TypeKey = EntityTypeKey[CounterCommand]("Counter")
  // if a extractor is defined then the type would be ActorRef[BasicCommand]
  val shardRegion: ActorRef[ShardingEnvelope[CounterCommand]] = sharding.spawn[CounterCommand](
    behavior = counter(0),
    props = Props.empty,
    typeKey = TypeKey,
    settings = ClusterShardingSettings(system),
    maxNumberOfShards = 10,
    handOffStopMessage = GoodByeCounter)
  //#spawn

  //#send
  // With an EntityRef
  val counterOne: EntityRef[CounterCommand] = sharding.entityRefFor(TypeKey, "counter-1")
  counterOne ! Increment

  // Entity id is specified via an `ShardingEnvelope`
  shardRegion ! ShardingEnvelope("counter-1", Increment)
  //#send

  //#persistence
  val ShardingTypeName = EntityTypeKey[BlogCommand]("BlogPost")
  ClusterSharding(system).spawn[BlogCommand](
    behavior = InDepthPersistentBehaviorSpec.behavior,
    props = Props.empty,
    typeKey = ShardingTypeName,
    settings = ClusterShardingSettings(system),
    maxNumberOfShards = 10,
    handOffStopMessage = PassivatePost)
  //#persistence

  // as a singleton

  //#singleton
  val singletonManager = ClusterSingleton(system)
  // Start if needed and provide a proxy to a named singleton
  val proxy: ActorRef[CounterCommand] = singletonManager.spawn(
    behavior = counter(0),
    "GlobalCounter",
    Props.empty,
    ClusterSingletonSettings(system),
    terminationMessage = GoodByeCounter
  )

  proxy ! Increment
  //#singleton

}
