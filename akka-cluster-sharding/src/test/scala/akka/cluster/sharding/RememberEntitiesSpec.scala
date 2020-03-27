/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

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
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object RememberEntitiesSpec {
  class EntityActor extends Actor {
    override def receive: Receive = {
      case "give-me-shard" => sender() ! context.parent
      case msg             => sender() ! msg
    }
  }

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id % 10).toString
    case StartEntity(id)       => (id.toInt % 10).toString
  }

  val config = ConfigFactory.parseString("""
      akka.loglevel=info
      akka.actor.provider = cluster
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)
}

class RememberEntitiesSpec extends AkkaSpec(RememberEntitiesSpec.config) with AnyWordSpecLike with ImplicitSender {

  import RememberEntitiesSpec._

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Persistent Shard" must {

    "remember entities started with StartEntity" in {
      val sharding = ClusterSharding(system).start(
        s"startEntity",
        Props[EntityActor],
        ClusterShardingSettings(system)
          .withRememberEntities(true)
          .withStateStoreMode(ClusterShardingSettings.StateStoreModePersistence),
        extractEntityId,
        extractShardId)

      sharding ! StartEntity("1")
      expectMsg(StartEntityAck("1", "1"))
      val shard = lastSender

      watch(shard)
      shard ! PoisonPill
      expectTerminated(shard)

      // trigger shard start by messaging other actor in it
      system.log.info("Starting shard again")
      sharding ! EntityEnvelope(11, "give-me-shard")
      val secondShardIncarnation = expectMsgType[ActorRef]

      awaitAssert {
        secondShardIncarnation ! GetShardStats
        // the remembered 1 and 11 which we just triggered start of
        expectMsg(ShardStats("1", 2))
      }
    }
  }

}
