/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, PoisonPill, Props }
import akka.cluster.sharding.PersistentShardSpec.EntityActor
import akka.cluster.sharding.Shard.{ GetShardStats, ShardStats }
import akka.cluster.sharding.ShardRegion.{ StartEntity, StartEntityAck }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object PersistentShardSpec {
  class EntityActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }

  val config = ConfigFactory.parseString("""
      akka.loglevel=debug
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)
}

class PersistentShardSpec extends AkkaSpec(PersistentShardSpec.config) with AnyWordSpecLike with ImplicitSender {

  "Persistent Shard" must {

    "remember entities started with StartEntity" in {
      val props =
        Props(
          new Shard(
            "cats",
            "shard-1",
            _ => Props(new EntityActor),
            ClusterShardingSettings(system)
              .withRememberEntities(true)
              .withStateStoreMode(ClusterShardingSettings.StateStoreModePersistence),
            system.deadLetters, {
              case _ => ("entity-1", "msg")
            }, { _ =>
              "shard-1"
            },
            PoisonPill,
            0))
      val persistentShard = system.actorOf(props, "shard-1")
      watch(persistentShard)

      persistentShard ! StartEntity("entity-1")
      expectMsg(StartEntityAck("entity-1", "shard-1"))

      persistentShard ! PoisonPill
      expectTerminated(persistentShard)

      system.log.info("Starting shard again")
      val secondIncarnation = system.actorOf(props)

      // FIXME how did this ever work when the RestartEntity goes through the parent, expected to be a shard region?
      secondIncarnation ! GetShardStats
      awaitAssert(expectMsg(ShardStats("shard-1", 1)))
    }
  }

}
