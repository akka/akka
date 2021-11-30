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

  val leastRecentlyUsedWithIdleConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = least-recently-used
        least-recently-used {
          limit = 3
          idle.timeout = 1s
        }
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

  val mostRecentlyUsedWithIdleConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = most-recently-used
        most-recently-used {
          limit = 3
          idle.timeout = 1s
        }
      }
    }
    """).withFallback(config)

  val leastFrequentlyUsedConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = least-frequently-used
        least-frequently-used.limit = 10
      }
    }
    """).withFallback(config)

  val leastFrequentlyUsedWithDynamicAgingConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = least-frequently-used
        least-frequently-used {
          limit = 10
          dynamic-aging = on
        }
      }
    }
    """).withFallback(config)

  val leastFrequentlyUsedWithIdleConfig = ConfigFactory.parseString("""
    akka.cluster.sharding {
      passivation {
        strategy = least-frequently-used
        least-frequently-used {
          limit = 3
          idle.timeout = 1s
        }
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
  val configuredIdleTimeout: FiniteDuration = settings.passivationStrategySettings.idleSettings.timeout
  val configuredLeastRecentlyUsedLimit: Int = settings.passivationStrategySettings.leastRecentlyUsedSettings.limit

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

class LeastRecentlyUsedWithIdleEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.leastRecentlyUsedWithIdleConfig, expectedEntities = 3) {

  import EntityPassivationSpec.Entity.{ Envelope, Stop }

  "Passivation of idle entities with least recently used strategy" must {
    "passivate entities when they haven't seen messages for the configured timeout" in {
      val region = start()

      val idleTimeout = settings.passivationStrategySettings.leastRecentlyUsedSettings.idleSettings.get.timeout

      val lastSendNanoTime1 = System.nanoTime()
      region ! Envelope(shard = 1, id = 1, message = "A")
      region ! Envelope(shard = 1, id = 2, message = "B")

      // keep entity 3 active to prevent idle passivation
      region ! Envelope(shard = 1, id = 3, message = "C")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "D")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "E")
      Thread.sleep((idleTimeout / 2).toMillis)
      val lastSendNanoTime2 = System.nanoTime()
      region ! Envelope(shard = 1, id = 3, message = "F")

      expectReceived(id = 1, message = "A")
      expectReceived(id = 2, message = "B")
      expectReceived(id = 3, message = "C")
      expectReceived(id = 3, message = "D")
      expectReceived(id = 3, message = "E")
      expectReceived(id = 3, message = "F")
      val passivate1 = expectReceived(id = 1, message = Stop)
      val passivate2 = expectReceived(id = 2, message = Stop)
      val passivate3 = expectReceived(id = 3, message = Stop, within = idleTimeout * 2)

      // note: touched timestamps are when the shard receives the message, not the entity itself
      // so look at the time from before sending the last message until receiving the passivate message
      (passivate1.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate2.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate3.nanoTime - lastSendNanoTime2).nanos should be > idleTimeout
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

class MostRecentlyUsedWithIdleEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.mostRecentlyUsedWithIdleConfig, expectedEntities = 3) {

  import EntityPassivationSpec.Entity.{ Envelope, Stop }

  "Passivation of idle entities with most recently used strategy" must {
    "passivate entities when they haven't seen messages for the configured timeout" in {
      val region = start()

      val idleTimeout = settings.passivationStrategySettings.mostRecentlyUsedSettings.idleSettings.get.timeout

      val lastSendNanoTime1 = System.nanoTime()
      region ! Envelope(shard = 1, id = 1, message = "A")
      region ! Envelope(shard = 1, id = 2, message = "B")

      // keep entity 3 active to prevent idle passivation
      region ! Envelope(shard = 1, id = 3, message = "C")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "D")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "E")
      Thread.sleep((idleTimeout / 2).toMillis)
      val lastSendNanoTime2 = System.nanoTime()
      region ! Envelope(shard = 1, id = 3, message = "F")

      expectReceived(id = 1, message = "A")
      expectReceived(id = 2, message = "B")
      expectReceived(id = 3, message = "C")
      expectReceived(id = 3, message = "D")
      expectReceived(id = 3, message = "E")
      expectReceived(id = 3, message = "F")
      val passivate1 = expectReceived(id = 1, message = Stop)
      val passivate2 = expectReceived(id = 2, message = Stop)
      val passivate3 = expectReceived(id = 3, message = Stop, within = idleTimeout * 2)

      // note: touched timestamps are when the shard receives the message, not the entity itself
      // so look at the time from before sending the last message until receiving the passivate message
      (passivate1.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate2.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate3.nanoTime - lastSendNanoTime2).nanos should be > idleTimeout
    }
  }
}

class LeastFrequentlyUsedEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.leastFrequentlyUsedConfig, expectedEntities = 40) {

  import EntityPassivationSpec.Entity.{ Envelope, ManuallyPassivate, Stop }

  "Passivation of least frequently used entities" must {
    "passivate the least frequently used entities when the per-shard entity limit is reached" in {
      val region = start()

      // only one active shard at first, least frequently used entities passivated once the limit is reached
      for (id <- 1 to 20) {
        val accesses = (id - 1) % 4 + 1 // vary frequency between 1 and 4 accesses
        for (x <- 1 to accesses) {
          region ! Envelope(shard = 1, id = id, message = s"A$x")
          expectReceived(id = id, message = s"A$x")
        }
        val expectPassivated =
          Map(11 -> 1, 12 -> 5, 13 -> 9, 14 -> 13, 15 -> 2, 16 -> 6, 17 -> 10, 18 -> 17, 19 -> 14, 20 -> 18)
        expectPassivated.get(id).foreach(passivated => expectReceived(id = passivated, message = Stop))
      }

      // shard 1: frequency 3 = (3, 7, 11, 15, 19), frequency 4 = (4, 8, 12, 16, 20)
      expectState(region)(1 -> Set(3, 7, 11, 15, 19, 4, 8, 12, 16, 20))

      // activating a second shard will divide the per-shard limit in two, passivating half of the first shard
      region ! Envelope(shard = 2, id = 21, message = "B")
      expectReceived(id = 21, message = "B")
      for (id <- Seq(3, 7, 11, 15, 19)) {
        expectReceived(id = id, message = Stop)
      }

      // shard 1: frequency 4 = (4, 8, 12, 16, 20)
      // shard 2: frequency 1 = (21)
      expectState(region)(1 -> Set(4, 8, 12, 16, 20), 2 -> Set(21))

      // shards now have a limit of 5 entities
      // note: newly added entities are not counted as least frequent for passivation
      for (id <- 1 to 20) {
        val accesses = (id - 1) % 4 + 1 // vary frequency between 1 and 4 accesses
        for (x <- 1 to accesses) {
          region ! Envelope(shard = 1, id = id, message = s"C$x")
          expectReceived(id = id, message = s"C$x")
        }
        val passivated = if (accesses == 1) id + 3 else id - 1
        expectReceived(id = passivated, message = Stop)
      }

      // shard 1: frequency 4 = (4, 8, 12, 16, 20)
      // shard 2: frequency 1 = (21)
      expectState(region)(1 -> Set(4, 8, 12, 16, 20), 2 -> Set(21))

      // shards now have a limit of 5 entities
      for (id <- 21 to 24) {
        region ! Envelope(shard = 2, id = id, message = "D")
        expectReceived(id = id, message = "D")
      }

      // shard 1: frequency 4 = (4, 8, 12, 16, 20)
      // shard 2: frequency 1 = (22, 23, 24), frequency 2 = (21)
      expectState(region)(1 -> Set(4, 8, 12, 16, 20), 2 -> Set(22, 23, 24, 21))

      // activating a third shard will divide the per-shard limit in three, passivating entities over the new limits
      region ! Envelope(shard = 3, id = 31, message = "E")
      expectReceived(id = 31, message = "E")
      for (id <- Seq(4, 8, 22)) {
        expectReceived(id = id, message = Stop)
      }

      // shard 1: frequency 4 = (12, 16, 20)
      // shard 2: frequency 1 = (23, 24), frequency 2 = (21)
      // shard 3: frequency 1 = (31)
      expectState(region)(1 -> Set(12, 16, 20), 2 -> Set(23, 24, 21), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 21 to 30) {
        val accesses = (id - 1) % 4 + 1 // vary frequency between 1 and 4 accesses
        for (x <- 1 to accesses) {
          region ! Envelope(shard = 2, id = id, message = s"F$x")
          expectReceived(id = id, message = s"F$x")
        }
        val expectPassivated =
          Map(22 -> 23, 23 -> 24, 24 -> 22, 25 -> 21, 26 -> 25, 27 -> 26, 28 -> 23, 29 -> 27, 30 -> 29)
        expectPassivated.get(id).foreach(passivated => expectReceived(id = passivated, message = Stop))
      }

      // shard 1: frequency 4 = (12, 16, 20)
      // shard 2: frequency 2 = (30), frequency 4 = (24, 28)
      // shard 3: frequency 1 = (31)
      expectState(region)(1 -> Set(12, 16, 20), 2 -> Set(30, 24, 28), 3 -> Set(31))

      // shards now have a limit of 3 entities
      for (id <- 31 to 40) {
        val accesses = 4 - (id - 1) % 4 // vary frequency between 1 and 4 accesses
        for (x <- 1 to accesses) {
          region ! Envelope(shard = 3, id = id, message = s"G$x")
          expectReceived(id = id, message = s"G$x")
        }
        val expectPassivated = Map(34 -> 32, 35 -> 31, 36 -> 35, 37 -> 36, 38 -> 34, 39 -> 38, 40 -> 39)
        expectPassivated.get(id).foreach(passivated => expectReceived(id = passivated, message = Stop))
      }

      // shard 1: frequency 4 = (12, 16, 20)
      // shard 2: frequency 2 = (30), frequency 4 = (24, 28)
      // shard 3: frequency 1 = (40), frequency 4 = (33, 37)
      expectState(region)(1 -> Set(12, 16, 20), 2 -> Set(30, 24, 28), 3 -> Set(40, 33, 37))

      // manually passivate some entities
      region ! Envelope(shard = 1, id = 16, message = ManuallyPassivate)
      region ! Envelope(shard = 2, id = 24, message = ManuallyPassivate)
      region ! Envelope(shard = 3, id = 33, message = ManuallyPassivate)
      expectReceived(id = 16, message = ManuallyPassivate)
      expectReceived(id = 24, message = ManuallyPassivate)
      expectReceived(id = 33, message = ManuallyPassivate)
      expectReceived(id = 16, message = Stop)
      expectReceived(id = 24, message = Stop)
      expectReceived(id = 33, message = Stop)

      // shard 1: frequency 4 = (12, 20)
      // shard 2: frequency 2 = (30), frequency 4 = (28)
      // shard 3: frequency 1 = (40), frequency 4 = (37)
      expectState(region)(1 -> Set(12, 20), 2 -> Set(30, 28), 3 -> Set(40, 37))

      for (i <- 1 to 3) {
        for (x <- 1 to i) {
          region ! Envelope(shard = 1, id = 10 + i, message = s"H$x")
          region ! Envelope(shard = 2, id = 20 + i, message = s"H$x")
          region ! Envelope(shard = 3, id = 30 + i, message = s"H$x")
          expectReceived(id = 10 + i, message = s"H$x")
          expectReceived(id = 20 + i, message = s"H$x")
          expectReceived(id = 30 + i, message = s"H$x")
        }
        if (i == 2) {
          expectReceived(id = 21, message = Stop)
          expectReceived(id = 40, message = Stop)
        } else if (i == 3) {
          expectReceived(id = 11, message = Stop)
          expectReceived(id = 30, message = Stop)
          expectReceived(id = 31, message = Stop)
        }
      }

      // shard 1: frequency 3 = (13), frequency 4 = (20), frequency 6 = (12)
      // shard 2: frequency 2 = (22), frequency 3 = (23), frequency 4 = (28)
      // shard 3: frequency 2 = (32), frequency 3 = (33), frequency 4 = (37)
      expectState(region)(1 -> Set(13, 20, 12), 2 -> Set(22, 23, 28), 3 -> Set(32, 33, 37))
    }
  }
}

class LeastFrequentlyUsedWithDynamicAgingEntityPassivationSpec
    extends AbstractEntityPassivationSpec(
      EntityPassivationSpec.leastFrequentlyUsedWithDynamicAgingConfig,
      expectedEntities = 21) {

  import EntityPassivationSpec.Entity.{ Envelope, Stop }

  "Passivation of least frequently used entities with dynamic aging" must {
    "passivate the least frequently used entities when the per-shard entity limit is reached" in {
      val region = start()

      // only one active shard at first
      // ids 1 and 2 are quite popular initially
      for (id <- 1 to 2) {
        for (x <- 1 to 5) {
          region ! Envelope(shard = 1, id = id, message = s"A$x")
          expectReceived(id = id, message = s"A$x")
        }
      }
      // ids 3, 4, and 5 are very popular initially
      for (id <- 3 to 5) {
        for (x <- 1 to 10) {
          region ! Envelope(shard = 1, id = id, message = s"A$x")
          expectReceived(id = id, message = s"A$x")
        }
      }

      // shard 1: age = 0, @5 (5 + 0) = (1, 2), @10 (10 + 0) = (3, 4, 5)
      expectState(region)(1 -> Set(1, 2, 3, 4, 5))

      for (id <- 6 to 20) {
        region ! Envelope(shard = 1, id = id, message = s"B")
        expectReceived(id = id, message = s"B")
        if (id > 10) expectReceived(id = id - 5, message = Stop)
      }

      // shard 1: age = 2, @3 (1 + 2) = (16, 17, 18, 19, 20), @5 (5 + 0) = (1, 2), @10 (10 + 0) = (3, 4, 5)
      expectState(region)(1 -> Set(1, 2, 3, 4, 5, 16, 17, 18, 19, 20))

      // activating a second shard will divide the per-shard limit in two, passivating half of the first shard
      region ! Envelope(shard = 2, id = 21, message = "C")
      expectReceived(id = 21, message = "C")
      for (id <- 16 to 20) expectReceived(id, message = Stop)

      // shard 1: age = 3, @5 (5 + 0) = (1, 2), @10 (10 + 0) = (3, 4, 5)
      // shard 2: age = 0, @1 (1 + 0) = (21)
      expectState(region)(1 -> Set(1, 2, 3, 4, 5), 2 -> Set(21))

      for (id <- 6 to 10) {
        region ! Envelope(shard = 1, id = id, message = s"D")
        expectReceived(id = id, message = s"D")
      }

      // shard 1: age = 7, @7 (1 + 6) = (9), @8 (1 + 7) = (10), @10 (10 + 0) = (3, 4, 5)
      // shard 2: age = 0, @1 (1 + 0) = (21)
      expectState(region)(1 -> Set(9, 10, 3, 4, 5), 2 -> Set(21))

      for (id <- 11 to 15) {
        region ! Envelope(shard = 1, id = id, message = s"E")
        expectReceived(id = id, message = s"E")
      }

      // shard 1: age = 9, @10 (10 + 0) = (3, 4, 5), @10 (1 + 9) = (14, 15)
      // shard 2: age = 0, @1 (1 + 0) = (21)
      expectState(region)(1 -> Set(3, 4, 5, 14, 15), 2 -> Set(21))

      for (id <- 16 to 20) {
        region ! Envelope(shard = 1, id = id, message = s"F")
        expectReceived(id = id, message = s"F")
      }

      // shard 1: age = 10, @11 (1 + 10) = (16, 17, 18, 19, 20)
      // shard 2: age = 0, @1 (1 + 0) = (21)
      expectState(region)(1 -> Set(16, 17, 18, 19, 20), 2 -> Set(21))
    }
  }
}

class LeastFrequentlyUsedWithIdleEntityPassivationSpec
    extends AbstractEntityPassivationSpec(EntityPassivationSpec.leastFrequentlyUsedWithIdleConfig, expectedEntities = 3) {

  import EntityPassivationSpec.Entity.{ Envelope, Stop }

  "Passivation of idle entities with least frequently used strategy" must {
    "passivate entities when they haven't seen messages for the configured timeout" in {
      val region = start()

      val idleTimeout = settings.passivationStrategySettings.leastFrequentlyUsedSettings.idleSettings.get.timeout

      val lastSendNanoTime1 = System.nanoTime()
      region ! Envelope(shard = 1, id = 1, message = "A")
      region ! Envelope(shard = 1, id = 2, message = "B")

      // keep entity 3 active to prevent idle passivation
      region ! Envelope(shard = 1, id = 3, message = "C")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "D")
      Thread.sleep((idleTimeout / 2).toMillis)
      region ! Envelope(shard = 1, id = 3, message = "E")
      Thread.sleep((idleTimeout / 2).toMillis)
      val lastSendNanoTime2 = System.nanoTime()
      region ! Envelope(shard = 1, id = 3, message = "F")

      expectReceived(id = 1, message = "A")
      expectReceived(id = 2, message = "B")
      expectReceived(id = 3, message = "C")
      expectReceived(id = 3, message = "D")
      expectReceived(id = 3, message = "E")
      expectReceived(id = 3, message = "F")
      val passivate1 = expectReceived(id = 1, message = Stop)
      val passivate2 = expectReceived(id = 2, message = Stop)
      val passivate3 = expectReceived(id = 3, message = Stop, within = idleTimeout * 2)

      // note: touched timestamps are when the shard receives the message, not the entity itself
      // so look at the time from before sending the last message until receiving the passivate message
      (passivate1.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate2.nanoTime - lastSendNanoTime1).nanos should be > idleTimeout
      (passivate3.nanoTime - lastSendNanoTime2).nanos should be > idleTimeout
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
