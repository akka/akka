/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.testkit.AkkaSpec
import akka.testkit.TestActors
import scala.concurrent.duration.FiniteDuration

object ProxyShardingSpec {
  val config = """
  akka.actor.provider = "cluster"
  akka.remote.classic.netty.tcp.port = 0
  akka.remote.artery.canonical.port = 0
  """
}

class ProxyShardingSpec extends AkkaSpec(ProxyShardingSpec.config) {

  val role = "Shard"
  val clusterSharding: ClusterSharding = ClusterSharding(system)
  val shardingSettings: ClusterShardingSettings =
    ClusterShardingSettings.create(system)
  val messageExtractor = new ShardRegion.HashCodeMessageExtractor(10) {
    override def entityId(message: Any) = "dummyId"
  }

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg @ id => (id.toString, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case id: Int => id.toString
  }

  val shardProxy: ActorRef =
    clusterSharding.startProxy("myType", Some(role), idExtractor, shardResolver)

  "Proxy should be found" in {
    val proxyActor: ActorRef = Await.result(
      system
        .actorSelection("akka://ProxyShardingSpec/system/sharding/myTypeProxy")
        .resolveOne(FiniteDuration(5, SECONDS)),
      3.seconds)

    proxyActor.path should not be null
    proxyActor.path.toString should endWith("Proxy")
  }

  "Shard region should be found" in {
    val shardRegion: ActorRef =
      clusterSharding.start("myType", TestActors.echoActorProps, shardingSettings, messageExtractor)

    shardRegion.path should not be null
    shardRegion.path.toString should endWith("myType")
  }

  "Shard coordinator should be found" in {
    val shardCoordinator: ActorRef =
      Await.result(
        system
          .actorSelection("akka://ProxyShardingSpec/system/sharding/myTypeCoordinator")
          .resolveOne(FiniteDuration(5, SECONDS)),
        3.seconds)

    shardCoordinator.path should not be null
    shardCoordinator.path.toString should endWith("Coordinator")
  }
}
