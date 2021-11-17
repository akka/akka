/*
 * Copyright (C) 2009-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import akka.actor.{ Actor, ActorRef, Props }
import akka.cluster.Cluster
import akka.testkit.WithLogCapturing
import akka.testkit.{ AkkaSpec, TestProbe }
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

object EntityPassivationSpec {

  val config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.actor.provider = "cluster"
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.cluster.sharding.verbose-debug-logging = on
    akka.cluster.sharding.fail-on-invalid-entity-state-transition = on
    """)

  val idleConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = idle
        idle.timeout = 1s
      }
    }
    """).withFallback(config)

  val leastRecentlyUsedConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = least-recently-used
        least-recently-used.limit = 10
      }
    }
    """).withFallback(config)

  val mostRecentlyUsedConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = most-recently-used
        most-recently-used.limit = 10
      }
    }
    """).withFallback(config)

  val disabledConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = none
        idle.timeout = 1s
      }
    }
    """).withFallback(config)

  object Entity {
    case object Stop
    case object ManuallyPassivate
    case class Envelope(shard: Int, id: Int, message: Any)
    case class Received(id: String, message: Any, nanoTime: Long)

    def props(probes: Map[String, ActorRef]) = Props(new Entity(probes))
  }

  class Entity(probes: Map[String, ActorRef]) extends Actor {
    def id = context.self.path.name

    def received(message: Any) = probes(id) ! Entity.Received(id, message, System.nanoTime())

    def receive = {
      case Entity.Stop =>
        received(Entity.Stop)
        context.stop(self)
      case Entity.ManuallyPassivate =>
        received(Entity.ManuallyPassivate)
        context.parent ! ShardRegion.Passivate(Entity.Stop)
      case msg => received(msg)
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case Entity.Envelope(_, id, message) => (id.toString, message)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Entity.Envelope(shard, _, _) => shard.toString
    case _                            => throw new IllegalArgumentException
  }
}

abstract class AbstractEntityPassivationSpec(config: Config, expectedEntities: Int)
    extends AkkaSpec(config)
    with Eventually
    with WithLogCapturing {

  import EntityPassivationSpec._

  val settings: ClusterShardingSettings = ClusterShardingSettings(system)
  val configuredIdleTimeout: FiniteDuration = settings.passivationStrategySettings.idleTimeout
  val configuredLeastRecentlyUsedLimit: Int = settings.passivationStrategySettings.leastRecentlyUsedLimit

  val probes: Map[Int, TestProbe] = (1 to expectedEntities).map(id => id -> TestProbe()).toMap
  val probeRefs: Map[String, ActorRef] = probes.map { case (id, probe) => id.toString -> probe.ref }
  val stateProbe: TestProbe = TestProbe()

  def expectReceived(id: Int, message: Any, within: FiniteDuration = patience.timeout): Entity.Received = {
    val received = probes(id).expectMsgType[Entity.Received](within)
    received.message shouldBe message
    received
  }

  def expectNoMessage(id: Int, within: FiniteDuration): Unit =
    probes(id).expectNoMessage(within)

  def getState(region: ActorRef): ShardRegion.CurrentShardRegionState = {
    region.tell(ShardRegion.GetShardRegionState, stateProbe.ref)
    stateProbe.expectMsgType[ShardRegion.CurrentShardRegionState]
  }

  def expectState(region: ActorRef)(expectedShards: (Int, Iterable[Int])*): Unit =
    eventually {
      getState(region).shards should contain theSameElementsAs expectedShards.map {
        case (shardId, entityIds) => ShardRegion.ShardState(shardId.toString, entityIds.map(_.toString).toSet)
      }
    }

  def start(): ActorRef = {
    // single node cluster
    Cluster(system).join(Cluster(system).selfAddress)
    ClusterSharding(system).start(
      "myType",
      EntityPassivationSpec.Entity.props(probeRefs),
      settings,
      extractEntityId,
      extractShardId,
      ClusterSharding(system).defaultShardAllocationStrategy(settings),
      Entity.Stop)
  }
}

class IdleEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.idleConfig, expectedEntities = 2) {

  import EntityPassivationSpec.Entity.{ Envelope, Stop }

  "Passivation of idle entities" must {
    "passivate entities when they haven't seen messages for the configured duration" in {
      val region = start()

      val lastSendNanoTime1 = System.nanoTime()
      region ! Envelope(shard = 1, id = 1, message = "A")
      region ! Envelope(shard = 2, id = 2, message = "B")
      Thread.sleep((configuredIdleTimeout / 2).toMillis)
      region ! Envelope(shard = 2, id = 2, message = "C")
      Thread.sleep((configuredIdleTimeout / 2).toMillis)
      region ! Envelope(shard = 2, id = 2, message = "D")
      Thread.sleep((configuredIdleTimeout / 2).toMillis)
      val lastSendNanoTime2 = System.nanoTime()
      region ! Envelope(shard = 2, id = 2, message = "E")

      expectReceived(id = 1, message = "A")
      expectReceived(id = 2, message = "B")
      expectReceived(id = 2, message = "C")
      expectReceived(id = 2, message = "D")
      expectReceived(id = 2, message = "E")
      val passivate1 = expectReceived(id = 1, message = Stop)
      val passivate2 = expectReceived(id = 2, message = Stop, within = configuredIdleTimeout * 2)

      // note: touched timestamps are when the shard receives the message, not the entity itself
      // so look at the time from before sending the last message until receiving the passivate message
      (passivate1.nanoTime - lastSendNanoTime1).nanos should be > configuredIdleTimeout
      (passivate2.nanoTime - lastSendNanoTime2).nanos should be > configuredIdleTimeout

      // entities can be re-activated
      region ! Envelope(shard = 1, id = 1, message = "X")
      region ! Envelope(shard = 2, id = 2, message = "Y")
      region ! Envelope(shard = 1, id = 1, message = "Z")

      expectReceived(id = 1, message = "X")
      expectReceived(id = 2, message = "Y")
      expectReceived(id = 1, message = "Z")
      expectReceived(id = 1, message = Stop, within = configuredIdleTimeout * 2)
      expectReceived(id = 2, message = Stop, within = configuredIdleTimeout * 2)
    }
  }
}

class LeastRecentlyUsedEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.leastRecentlyUsedConfig, expectedEntities = 40) {

  import EntityPassivationSpec.Entity.{ Envelope, ManuallyPassivate, Stop }

  "Passivation of least recently used entities" must {
    "passivate the least recently used entities when the per-shard entity limit is reached" in {
      val region = start()

      // only one active shard at first, least recently used entities passivated once the limit is reached
      for (id <- 1 to 20) {
        region ! Envelope(shard = 1, id = id, message = "A")
        expectReceived(id = id, message = "A")
        if (id > 10) expectReceived(id = id - 10, message = Stop)
      }

      expectState(region)(1 -> (11 to 20))

      // activating a second shard will divide the per-shard limit in two, passivating half of the first shard
      region ! Envelope(shard = 2, id = 21, message = "B")
      expectReceived(id = 21, message = "B")
      for (id <- 11 to 15) {
        expectReceived(id = id, message = Stop)
      }

      expectState(region)(1 -> (16 to 20), 2 -> Set(21))

      // shards now have a limit of 5 entities
      for (id <- 1 to 20) {
        region ! Envelope(shard = 1, id = id, message = "C")
        expectReceived(id = id, message = "C")
        val passivatedId = if (id <= 5) id + 15 else id - 5
        expectReceived(id = passivatedId, message = Stop)
      }

      expectState(region)(1 -> (16 to 20), 2 -> Set(21))

      // shards now have a limit of 5 entities
      for (id <- 21 to 24) {
        region ! Envelope(shard = 2, id = id, message = "D")
        expectReceived(id = id, message = "D")
      }

      expectState(region)(1 -> (16 to 20), 2 -> (21 to 24))

      // activating a third shard will divide the per-shard limit in three, passivating entities over the new limits
      region ! Envelope(shard = 3, id = 31, message = "E")
      expectReceived(id = 31, message = "E")
      for (id <- Seq(16, 17, 21)) {
        expectReceived(id = id, message = Stop)
      }

      expectState(region)(1 -> Set(18, 19, 20), 2 -> Set(22, 23, 24), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 25 to 30) {
        region ! Envelope(shard = 2, id = id, message = "F")
        expectReceived(id = id, message = "F")
        expectReceived(id = id - 3, message = Stop)
      }

      expectState(region)(1 -> Set(18, 19, 20), 2 -> Set(28, 29, 30), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 31 to 40) {
        region ! Envelope(shard = 3, id = id, message = "G")
        expectReceived(id = id, message = "G")
        if (id > 33) expectReceived(id = id - 3, message = Stop)
      }

      expectState(region)(1 -> Set(18, 19, 20), 2 -> Set(28, 29, 30), 3 -> Set(38, 39, 40))

      // manually passivate some entities
      region ! Envelope(shard = 1, id = 19, message = ManuallyPassivate)
      region ! Envelope(shard = 2, id = 29, message = ManuallyPassivate)
      region ! Envelope(shard = 3, id = 39, message = ManuallyPassivate)
      expectReceived(id = 19, message = ManuallyPassivate)
      expectReceived(id = 29, message = ManuallyPassivate)
      expectReceived(id = 39, message = ManuallyPassivate)
      expectReceived(id = 19, message = Stop)
      expectReceived(id = 29, message = Stop)
      expectReceived(id = 39, message = Stop)

      expectState(region)(1 -> Set(18, 20), 2 -> Set(28, 30), 3 -> Set(38, 40))

      for (i <- 1 to 3) {
        region ! Envelope(shard = 1, id = 10 + i, message = "H")
        region ! Envelope(shard = 2, id = 20 + i, message = "H")
        region ! Envelope(shard = 3, id = 30 + i, message = "H")
        expectReceived(id = 10 + i, message = "H")
        expectReceived(id = 20 + i, message = "H")
        expectReceived(id = 30 + i, message = "H")
        if (i == 2) {
          expectReceived(id = 18, message = Stop)
          expectReceived(id = 28, message = Stop)
          expectReceived(id = 38, message = Stop)
        } else if (i == 3) {
          expectReceived(id = 20, message = Stop)
          expectReceived(id = 30, message = Stop)
          expectReceived(id = 40, message = Stop)
        }
      }

      expectState(region)(1 -> Set(11, 12, 13), 2 -> Set(21, 22, 23), 3 -> Set(31, 32, 33))
    }
  }
}

class MostRecentlyUsedEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.mostRecentlyUsedConfig, expectedEntities = 40) {

  import EntityPassivationSpec.Entity.{ Envelope, ManuallyPassivate, Stop }

  "Passivation of most recently used entities" must {
    "passivate the most recently used entities when the per-shard entity limit is reached" in {
      val region = start()

      // only one active shard at first, most recently used entities passivated once the limit is reached
      for (id <- 1 to 20) {
        region ! Envelope(shard = 1, id = id, message = "A")
        expectReceived(id, message = "A")
        if (id > 10) expectReceived(id = id - 1, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 20))

      // activating a second shard will divide the per-shard limit in two, passivating half of the first shard
      region ! Envelope(shard = 2, id = 21, message = "B")
      expectReceived(id = 21, message = "B")
      for (id <- Seq(20, 9, 8, 7, 6)) {
        expectReceived(id, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3, 4, 5), 2 -> Set(21))

      // shards now have a limit of 5 entities
      for (id <- 1 to 20) {
        region ! Envelope(shard = 1, id = id, message = "C")
        expectReceived(id = id, message = "C")
        if (id > 5) expectReceived(id = id - 1, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3, 4, 20), 2 -> Set(21))

      // shards now have a limit of 5 entities
      for (id <- 21 to 24) {
        region ! Envelope(shard = 2, id = id, message = "D")
        expectReceived(id = id, message = "D")
      }

      expectState(region)(1 -> Set(1, 2, 3, 4, 20), 2 -> Set(21, 22, 23, 24))

      // activating a third shard will divide the per-shard limit in three, passivating entities over the new limits
      region ! Envelope(shard = 3, id = 31, message = "E")
      expectReceived(id = 31, message = "E")
      for (id <- Seq(24, 20, 4)) {
        expectReceived(id = id, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3), 2 -> Set(21, 22, 23), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 24 to 30) {
        region ! Envelope(shard = 2, id = id, message = "F")
        expectReceived(id = id, message = "F")
        expectReceived(id = id - 1, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3), 2 -> Set(21, 22, 30), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 31 to 40) {
        region ! Envelope(shard = 3, id = id, message = "G")
        expectReceived(id = id, message = "G")
        if (id > 33) expectReceived(id = id - 1, message = Stop)
      }

      expectState(region)(1 -> Set(1, 2, 3), 2 -> Set(21, 22, 30), 3 -> Set(31, 32, 40))

      // manually passivate some entities
      region ! Envelope(shard = 1, id = 2, message = ManuallyPassivate)
      region ! Envelope(shard = 2, id = 22, message = ManuallyPassivate)
      region ! Envelope(shard = 3, id = 32, message = ManuallyPassivate)
      expectReceived(id = 2, message = ManuallyPassivate)
      expectReceived(id = 22, message = ManuallyPassivate)
      expectReceived(id = 32, message = ManuallyPassivate)
      expectReceived(id = 2, message = Stop)
      expectReceived(id = 22, message = Stop)
      expectReceived(id = 32, message = Stop)

      expectState(region)(1 -> Set(1, 3), 2 -> Set(21, 30), 3 -> Set(31, 40))

      for (i <- 1 to 3) {
        region ! Envelope(shard = 1, id = 11 + i, message = "H")
        region ! Envelope(shard = 2, id = 22 + i, message = "H")
        region ! Envelope(shard = 3, id = 33 + i, message = "H")
        expectReceived(id = 11 + i, message = "H")
        expectReceived(id = 22 + i, message = "H")
        expectReceived(id = 33 + i, message = "H")
        if (i == 2) {
          expectReceived(id = 12, message = Stop)
          expectReceived(id = 23, message = Stop)
          expectReceived(id = 34, message = Stop)
        } else if (i == 3) {
          expectReceived(id = 13, message = Stop)
          expectReceived(id = 24, message = Stop)
          expectReceived(id = 35, message = Stop)
        }
      }

      expectState(region)(1 -> Set(1, 3, 14), 2 -> Set(21, 30, 25), 3 -> Set(31, 40, 36))
    }
  }
}

class DisabledEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.disabledConfig, expectedEntities = 1) {

  import EntityPassivationSpec.Entity.Envelope

  "Passivation of idle entities" must {
    "not passivate when passivation is disabled" in {
      settings.passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
      val region = start()
      region ! Envelope(shard = 1, id = 1, message = "A")
      expectReceived(id = 1, message = "A")
      expectNoMessage(id = 1, configuredIdleTimeout * 2)
    }
  }
}
