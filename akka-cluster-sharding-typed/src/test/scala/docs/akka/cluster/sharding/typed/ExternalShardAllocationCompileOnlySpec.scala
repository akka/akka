package docs.akka.cluster.sharding.typed

import akka.actor.Address
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.external.ExternalShardAllocation
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.external.scaladsl.ExternalShardAllocationClient
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import docs.akka.cluster.sharding.typed.ShardingCompileOnlySpec.Basics.Counter

class ExternalShardAllocationCompileOnlySpec {
  val system: ActorSystem[_] = ???

  val sharding = ClusterSharding(system)

  val TypeKey = EntityTypeKey[Counter.Command]("Counter")

  val shardRegion: ActorRef[ShardingEnvelope[Counter.Command]] =
    sharding.init(
      Entity(TypeKey)(createBehavior = entityContext => Counter(entityContext.entityId))
        .withAllocationStrategy(new ExternalShardAllocationStrategy(system, TypeKey.name)))

  val client: ExternalShardAllocationClient = ExternalShardAllocation(system).clientFor(TypeKey.name)
  client.updateShardLocation("shard-id", Address("akka", "system", "127.0,0,1", 2552))

}
