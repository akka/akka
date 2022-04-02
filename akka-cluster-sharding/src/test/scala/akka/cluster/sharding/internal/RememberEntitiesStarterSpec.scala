/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal

import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.Shard
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.ShardId
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class RememberEntitiesStarterSpec extends AkkaSpec {

  var shardIdCounter = 1
  def nextShardId(): ShardId = {
    val id = s"ShardId$shardIdCounter"
    shardIdCounter += 1
    id
  }

  "The RememberEntitiesStarter" must {
    "try start all entities directly with entity-recovery-strategy = all (default)" in {
      val regionProbe = TestProbe()
      val shardProbe = TestProbe()
      val shardId = nextShardId()

      val defaultSettings = ClusterShardingSettings(system)

      val rememberEntityStarter = system.actorOf(
        RememberEntityStarter.props(regionProbe.ref, shardProbe.ref, shardId, Set("1", "2", "3"), defaultSettings))

      watch(rememberEntityStarter)
      val startedEntityIds = (1 to 3).map { _ =>
        val start = regionProbe.expectMsgType[ShardRegion.StartEntity]
        regionProbe.lastSender ! ShardRegion.StartEntityAck(start.entityId, shardId)
        start.entityId
      }.toSet
      startedEntityIds should ===(Set("1", "2", "3"))

      // the starter should then stop itself, not sending anything more to the shard or region
      expectTerminated(rememberEntityStarter)
      shardProbe.expectNoMessage()
      regionProbe.expectNoMessage()
    }

    "retry start all entities with no ack with entity-recovery-strategy = all (default)" in {
      val regionProbe = TestProbe()
      val shardProbe = TestProbe()
      val shardId = nextShardId()

      val customSettings = ClusterShardingSettings(
        ConfigFactory
          .parseString(
            // the restarter somewhat surprisingly uses this for no-ack-retry. Tune it down to speed up test
            """
             retry-interval = 1 second
            """)
          .withFallback(system.settings.config.getConfig("akka.cluster.sharding")))

      val rememberEntityStarter = system.actorOf(
        RememberEntityStarter.props(regionProbe.ref, shardProbe.ref, shardId, Set("1", "2", "3"), customSettings))

      watch(rememberEntityStarter)
      (1 to 3).foreach { _ =>
        regionProbe.expectMsgType[ShardRegion.StartEntity]
      }
      val startedOnSecondTry = (1 to 3).map { _ =>
        val start = regionProbe.expectMsgType[ShardRegion.StartEntity]
        regionProbe.lastSender ! ShardRegion.StartEntityAck(start.entityId, shardId)
        start.entityId
      }.toSet
      startedOnSecondTry should ===(Set("1", "2", "3"))

      // should stop itself, not sending anything to the shard
      expectTerminated(rememberEntityStarter)
      shardProbe.expectNoMessage()
    }

    "inform the shard when entities has been reallocated to different shard id" in {
      val regionProbe = TestProbe()
      val shardProbe = TestProbe()
      val shardId = nextShardId()

      val customSettings = ClusterShardingSettings(
        ConfigFactory
          .parseString(
            // the restarter somewhat surprisingly uses this for no-ack-retry. Tune it down to speed up test
            """
             retry-interval = 1 second
            """)
          .withFallback(system.settings.config.getConfig("akka.cluster.sharding")))

      val rememberEntityStarter = system.actorOf(
        RememberEntityStarter.props(regionProbe.ref, shardProbe.ref, shardId, Set("1", "2", "3"), customSettings))

      watch(rememberEntityStarter)
      val start1 = regionProbe.expectMsgType[ShardRegion.StartEntity]
      regionProbe.lastSender ! ShardRegion.StartEntityAck(start1.entityId, shardId) // keep on current shard

      val start2 = regionProbe.expectMsgType[ShardRegion.StartEntity]
      regionProbe.lastSender ! ShardRegion.StartEntityAck(start2.entityId, shardId = "Relocated1")

      val start3 = regionProbe.expectMsgType[ShardRegion.StartEntity]
      regionProbe.lastSender ! ShardRegion.StartEntityAck(start3.entityId, shardId = "Relocated2")

      shardProbe.expectMsg(Shard.EntitiesMovedToOtherShard(Set("2", "3")))
      expectTerminated(rememberEntityStarter)
    }

    "try start all entities in a throttled way with entity-recovery-strategy = constant" in {
      val regionProbe = TestProbe()
      val shardProbe = TestProbe()
      val shardId = nextShardId()

      val customSettings = ClusterShardingSettings(
        ConfigFactory
          .parseString(
            // slow constant restart
            """
             entity-recovery-strategy = constant
             entity-recovery-constant-rate-strategy {
               frequency = 2 s
               number-of-entities = 2
             }
             retry-interval = 1 second
            """)
          .withFallback(system.settings.config.getConfig("akka.cluster.sharding")))

      val rememberEntityStarter = system.actorOf(
        RememberEntityStarter
          .props(regionProbe.ref, shardProbe.ref, shardId, Set("1", "2", "3", "4", "5"), customSettings))

      def recieveStartAndAck() = {
        val start = regionProbe.expectMsgType[ShardRegion.StartEntity]
        regionProbe.lastSender ! ShardRegion.StartEntityAck(start.entityId, shardId)
      }

      watch(rememberEntityStarter)
      // first batch should be immediate
      recieveStartAndAck()
      recieveStartAndAck()
      // second batch holding off (with some room for unstable test env)
      regionProbe.expectNoMessage(600.millis)

      // second batch should be immediate
      recieveStartAndAck()
      recieveStartAndAck()
      // third batch holding off
      regionProbe.expectNoMessage(600.millis)

      recieveStartAndAck()

      // the starter should then stop itself, not sending anything more to the shard or region
      expectTerminated(rememberEntityStarter)
      shardProbe.expectNoMessage()
      regionProbe.expectNoMessage()
    }

  }
}
