/*
 * Copyright (C) 2018-2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.sharding.Shard.GetShardStats
import akka.cluster.sharding.Shard.ShardStats
import akka.cluster.sharding.ShardRegion.StartEntity
import akka.cluster.sharding.ShardRegion.StartEntityAck
import akka.cluster.sharding.internal.DDataRememberEntitiesShardStore
import akka.cluster.sharding.internal.RememberEntitiesShardStore
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.testkit.ImplicitSender
import akka.testkit.WithLogCapturing

object RememberEntitiesAndStartEntityDdataSpec {
  class EntityActor extends Actor {
    override def receive: Receive = {
      case "give-me-shard" => sender() ! context.parent
      case msg             => sender() ! msg
    }
  }

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
    case _                           => throw new IllegalArgumentException()
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id % 10).toString
    case StartEntity(id)       => (id.toInt % 10).toString
    case _                     => throw new IllegalArgumentException()
  }

  val config = ConfigFactory.parseString(s"""
      akka.loglevel=DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = cluster
      akka.remote.artery.canonical.port = 0
      akka.cluster.sharding.verbose-debug-logging = on
      akka.cluster.sharding.fail-on-invalid-entity-state-transition = on
      # no leaks between test runs thank you
      akka.cluster.sharding.distributed-data.durable.keys = []
    """.stripMargin)
}

// this test covers remember entities + StartEntity
class RememberEntitiesAndStartEntityDdataSpec
    extends AkkaSpec(RememberEntitiesAndStartEntityDdataSpec.config)
    with AnyWordSpecLike
    with ImplicitSender
    with WithLogCapturing {

  import RememberEntitiesAndStartEntityDdataSpec._

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Sharding" must {

    "remember entities started with StartEntity" in {
      val typeName = "startEntity"
      val shardingSettings = ClusterShardingSettings(system).withRememberEntities(true)
      val sharding =
        ClusterSharding(system).start(typeName, Props[EntityActor](), shardingSettings, extractEntityId, extractShardId)

      sharding ! StartEntity("1")
      expectMsg(StartEntityAck("1", "1"))
      val shard = lastSender

      watch(shard)
      shard ! PoisonPill
      expectTerminated(shard)

      // trigger shard start by messaging other actor in it
      system.log.info("Starting shard again")
      // race condition between this message and region getting the termination message, we may need to retry
      val secondShardIncarnation = awaitAssert {
        sharding ! EntityEnvelope(11, "give-me-shard")
        expectMsgType[ActorRef](1.second) // short timeout, retry via awaitAssert
      }

      awaitAssert {
        secondShardIncarnation ! GetShardStats
        // the remembered 1 and 11 which we just triggered start of
        expectMsg(1.second, ShardStats("1", 2)) // short timeout, retry via awaitAssert
      }

      // start another bunch of entities (without waiting for each to complete before starting the next)
      EventFilter
        .error(
          start = "Unknown message type akka.cluster.sharding.internal.RememberEntitiesShardStore$UpdateDone",
          occurrences = 0)
        .intercept {
          for (i <- 2 to 5) {
            // mix a few StartEntity and regular startups
            if (i % 2 == 0)
              sharding ! StartEntity((i * 10 + 1).toString)
            else
              sharding ! EntityEnvelope(i * 10 + 1, "give-me-shard")
          }
          Thread.sleep(100)
          for (i <- 6 to 9) {
            // mix a few StartEntity and regular startups
            if (i % 2 == 0)
              sharding ! StartEntity((i * 10 + 1).toString)
            else
              sharding ! EntityEnvelope(i * 10 + 1, "give-me-shard")
          }
        }

      // all started without error
      receiveN(8)

      awaitAssert {
        secondShardIncarnation ! GetShardStats
        // the new remembered 8 and previous 1 and 11 which we just triggered start of
        expectMsg(1.second, ShardStats("1", 10)) // short timeout, retry via awaitAssert
      }

      // check the entity starts written to ddata
      val replicator = system.actorSelection("/system/sharding/replicator").resolveOne(1.second).futureValue
      val store = system.actorOf(
        DDataRememberEntitiesShardStore.props(new ShardRegion.ShardId("1"), typeName, shardingSettings, replicator, 0),
        "dummyStore")
      store ! RememberEntitiesShardStore.GetEntities
      val remembered = expectMsgType[RememberEntitiesShardStore.RememberedEntities]
      remembered.entities shouldEqual Set("1", "11", "21", "31", "41", "51", "61", "71", "81", "91")
    }
  }

}
