/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import java.util.UUID

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.Cluster
import akka.persistence.PersistentActor
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import com.typesafe.config.ConfigFactory

/**
 * Test migration from old persistent shard coordinator with remembered
 * entities to using a ddatabacked shard coordinator with an event sourced
 * replicated entity store.
 */
object PersistentShardingMigrationSpec {
  val config = ConfigFactory.parseString(s"""
       akka.loglevel = WARNING
       akka.actor.provider = "cluster"
       akka.cluster.sharding {
        remember-entities = on
        remember-entities-store = "eventsourced"

        # this forces the remembered entity store to use persistence
        # is is deprecated
        state-store-mode = "persistence"
       
        # make sure we test snapshots
        snapshot-after = 2
       }
       
       akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
       akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
       akka.persistence.snapshot-store.local.dir = "target/PersistentShardingMigrationSpec-${UUID
    .randomUUID()
    .toString}"
       akka.persistence.journal.leveldb {
         native = off
          dir = "target/journal-PersistentShardingMigrationSpec-${UUID.randomUUID()}"
      }
      """)

  val configForAfterMigration = ConfigFactory
    .parseString(
      """
       akka.cluster.sharding {
        remember-entities = on
        remember-entities-store = "eventsourced"
        state-store-mode = "ddata"
       }
       
       akka.persistence.journal.leveldb {
        event-adapters {
          coordinator-migration = "akka.cluster.sharding.internal.EventSourcedRememberShards$FromOldCoordinatorState"
        }

        event-adapter-bindings {
          "akka.cluster.sharding.ShardCoordinator$Internal$DomainEvent"        = coordinator-migration
        }
      }
       
      """)
    .withFallback(config)

  case class Message(id: Long)

  class PA extends PersistentActor {
    override def persistenceId: String = "pa-" + self.path.name
    override def receiveRecover: Receive = {
      case _ =>
    }
    override def receiveCommand: Receive = {
      case _ =>
        sender() ! "ack"
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg @ Message(id) => (id.toString, msg)
  }

  def extractShardId(probe: ActorRef): ShardRegion.ExtractShardId = {
    case Message(id)                 => id.toString
    case ShardRegion.StartEntity(id) =>
      // StartEntity is used by remembering entities feature
      probe ! id
      id
  }
}

class PersistentShardingMigrationSpec extends AkkaSpec(PersistentShardingMigrationSpec.config) with ImplicitSender {

  import PersistentShardingMigrationSpec._

  "Migration" should {
    "work" in {
      Cluster(system).join(Cluster(system).selfAddress)

      {
        val rememberedEntitiesProbe = TestProbe()
        val region = ClusterSharding(system).start(
          "PA",
          Props(new PA()),
          extractEntityId,
          extractShardId(rememberedEntitiesProbe.ref))
        region ! Message(1)
        expectMsg("ack")
        region ! Message(2)
        expectMsg("ack")
        region ! Message(3)
        expectMsg("ack")
        system.terminate().futureValue
      }
      {
        val secondSystem = ActorSystem("PersistentMigration2", configForAfterMigration)
        val rememberedEntitiesProbe = TestProbe()(secondSystem)
        Cluster(secondSystem).join(Cluster(secondSystem).selfAddress)
        val region = ClusterSharding(secondSystem).start(
          "PA",
          Props(new PA()),
          extractEntityId,
          extractShardId(rememberedEntitiesProbe.ref))
        val probe = TestProbe()(secondSystem)
        region.tell(Message(1), probe.ref)
        probe.expectMsg("ack")
        Set(
          rememberedEntitiesProbe.expectMsgType[String],
          rememberedEntitiesProbe.expectMsgType[String],
          rememberedEntitiesProbe
            .expectMsgType[String]) shouldEqual Set("1", "2", "3") // 1-2 from the snapshot, 3 from a replayed message
        rememberedEntitiesProbe.expectNoMessage()
      }
    }
  }
}
