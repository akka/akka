/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Actor
import com.typesafe.config.ConfigFactory

object PersistentShardSpec {
  class EntityActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }

  /*
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
   */

  val config = ConfigFactory.parseString("""
      akka.loglevel=debug
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)
}

// FIXME replace this with something that uses sharding from the "outside" rather than creating parts of the inside
/*
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
 */
