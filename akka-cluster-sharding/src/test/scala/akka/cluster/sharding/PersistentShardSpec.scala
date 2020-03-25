/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorLogging
import akka.actor.{ Actor, PoisonPill, Props }
import akka.cluster.sharding.PersistentShardSpec.FakeShardRegion
import akka.cluster.sharding.Shard.{ GetShardStats, ShardStats }
import akka.cluster.sharding.ShardRegion.ShardInitialized
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

  object FakeShardRegion {
    val props = Props(new FakeShardRegion)
  }

  // Needed since the shard triggers restarts of remembered entities by messaging through its
  // parent shard region and the shard stats will respond with entities actually started
  class FakeShardRegion extends Actor with ActorLogging {

    val shardProps = Props(
      new Shard(
        "cats",
        "shard-1",
        _ => Props(new EntityActor),
        ClusterShardingSettings(context.system)
          .withRememberEntities(true)
          .withStateStoreMode(ClusterShardingSettings.StateStoreModePersistence),
        context.system.deadLetters, {
          case _ => ("entity-1", "msg")
        }, { _ =>
          "shard-1"
        },
        PoisonPill,
        0))

    val fakeShard = context.actorOf(shardProps)

    override def receive: Receive = {
      case _: ShardInitialized =>
      case msg =>
        log.debug("Fake shard region forwarding {}", msg)
        fakeShard.forward(msg)
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
      val props = FakeShardRegion.props

      val persistentShard = system.actorOf(props, "shard-1")
      watch(persistentShard)

      persistentShard ! StartEntity("entity-1")
      expectMsg(StartEntityAck("entity-1", "shard-1"))

      persistentShard ! PoisonPill
      expectTerminated(persistentShard)

      system.log.info("Starting shard again")
      val secondIncarnation = system.actorOf(props)

      awaitAssert {
        secondIncarnation ! GetShardStats
        expectMsg(ShardStats("shard-1", 1))
      }
    }
  }

}
