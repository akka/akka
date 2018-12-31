/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, PoisonPill, Props }
import akka.cluster.sharding.PersistentShardSpec.EntityActor
import akka.cluster.sharding.Shard.{ GetShardStats, ShardStats }
import akka.cluster.sharding.ShardRegion.{ StartEntity, StartEntityAck }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object PersistentShardSpec {
  class EntityActor(id: String) extends Actor {
    override def receive: Receive = {
      case _ ⇒
    }
  }

  val config = ConfigFactory.parseString(
    """
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)
}

class PersistentShardSpec extends AkkaSpec(PersistentShardSpec.config) with WordSpecLike with ImplicitSender {

  "Persistent Shard" must {

    "remember entities started with StartEntity" in {
      val props = Props(new PersistentShard(
        "cats",
        "shard-1",
        id ⇒ Props(new EntityActor(id)),
        ClusterShardingSettings(system),
        {
          case _ ⇒ ("entity-1", "msg")
        },
        _ ⇒ "shard-1",
        PoisonPill
      ))
      val persistentShard = system.actorOf(props)
      watch(persistentShard)

      persistentShard ! StartEntity("entity-1")
      expectMsg(StartEntityAck("entity-1", "shard-1"))

      persistentShard ! PoisonPill
      expectTerminated(persistentShard)

      system.log.info("Starting shard again")
      val secondIncarnation = system.actorOf(props)

      secondIncarnation ! GetShardStats
      awaitAssert(expectMsg(ShardStats("shard-1", 1)))
    }
  }

}
