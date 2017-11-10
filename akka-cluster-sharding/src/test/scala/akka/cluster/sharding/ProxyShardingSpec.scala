/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.testkit.AkkaSpec
import akka.actor.ActorSystem
import akka.testkit.TestActors
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.FiniteDuration

class ProxyShardingSpec extends AkkaSpec {

  val role = "Shard"
  val nodes = 5

  val configString =
    "akka.actor.provider=\"akka.cluster.ClusterActorRefProvider\""
  val config: Config = ConfigFactory
    .parseString(configString)
    .withFallback(ConfigFactory.defaultApplication)
  val mySystem = ActorSystem("mySystem", config)

  val clusterSharding: ClusterSharding = ClusterSharding.get(mySystem)
  val shardingSettings: ClusterShardingSettings =
    ClusterShardingSettings.create(mySystem)
  val messageExtractor = new ShardRegion.HashCodeMessageExtractor(10) {
    override def entityId(message: Any) = "dummyId"
  }

  val idExtractor: ShardRegion.ExtractEntityId = {
    case msg @ id ⇒ (id.toString, msg)
  }

  val shardResolver: ShardRegion.ExtractShardId = {
    case id: Int ⇒ id.toString
  }

  val shardProxy: ActorRef = clusterSharding.startProxy(
    "myTypeProxy",
    Some(role),
    idExtractor,
    shardResolver)

  val childOfGuardian: ActorRef = Await.result(
    mySystem
      .actorSelection("akka://mySystem/system/sharding/myTypeProxy")
      .resolveOne(FiniteDuration(5, SECONDS)),
    3.seconds)

  val shardRegion: ActorRef = clusterSharding.start(
    "myType",
    TestActors.echoActorProps,
    shardingSettings,
    messageExtractor)

  "Shard coordinator should be found" in {
    val shardCoordinator: ActorRef = Await.result(
      mySystem
        .actorSelection("akka://mySystem/system/sharding/myTypeCoordinator")
        .resolveOne(FiniteDuration(5, SECONDS)),
      3.seconds)

    childOfGuardian.path should not be null
    shardCoordinator.path should not be null
  }
}
