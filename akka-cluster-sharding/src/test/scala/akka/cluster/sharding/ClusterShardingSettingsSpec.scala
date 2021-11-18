/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.ActorSystem
import akka.testkit.{ AkkaSpec, TestKit }
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class ClusterShardingSettingsSpec extends AnyWordSpec with Matchers {

  def settings(conf: String): ClusterShardingSettings = {
    val config = ConfigFactory.parseString(conf).withFallback(AkkaSpec.testConf)
    val system = ActorSystem("ClusterShardingSettingsSpec", config)
    val clusterShardingSettings = ClusterShardingSettings(system)
    TestKit.shutdownActorSystem(system)
    clusterShardingSettings
  }

  val defaultSettings: ClusterShardingSettings = settings(conf = "")

  "ClusterShardingSettings" must {

    "have default passivation strategy (passivate idle entities after 120 seconds)" in {
      defaultSettings.passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(120.seconds)
    }

    "allow timeout for (default) idle passivation strategy to be configured (via config)" in {
      settings("""
        #passivation-idle-timeout
        akka.cluster.sharding {
          passivation {
            idle.timeout = 3 minutes
          }
        }
        #passivation-idle-timeout
      """).passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(3.minutes)
    }

    "allow timeout for (default) idle passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withIdlePassivationStrategy(timeout = 42.seconds)
        .passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(42.seconds)
    }

    "allow least recently used passivation strategy to be configured (via config)" in {
      settings("""
        #passivation-least-recently-used
        akka.cluster.sharding {
          passivation {
            strategy = least-recently-used
            least-recently-used.limit = 1000000
          }
        }
        #passivation-least-recently-used
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(1000000)
    }

    "allow least recently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withLeastRecentlyUsedPassivationStrategy(limit = 42000)
        .passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(42000)
    }

    "allow most recently used passivation strategy to be configured (via config)" in {
      settings("""
        #passivation-most-recently-used
        akka.cluster.sharding {
          passivation {
            strategy = most-recently-used
            most-recently-used.limit = 1000000
          }
        }
        #passivation-most-recently-used
      """).passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(1000000)
    }

    "allow most recently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withMostRecentlyUsedPassivationStrategy(limit = 42000)
        .passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(42000)
    }

    "allow least frequently used passivation strategy to be configured (via config)" in {
      settings("""
        #passivation-least-frequently-used
        akka.cluster.sharding {
          passivation {
            strategy = least-frequently-used
            least-frequently-used.limit = 1000000
          }
        }
        #passivation-least-frequently-used
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(1000000)
    }

    "allow least frequently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withLeastFrequentlyUsedPassivationStrategy(limit = 42000)
        .passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(42000)
    }

    "disable automatic passivation if `remember-entities` is enabled (via config)" in {
      settings("""
        akka.cluster.sharding.remember-entities = on
      """).passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "disable automatic passivation if `remember-entities` is enabled (via factory method)" in {
      defaultSettings
        .withRememberEntities(true)
        .passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "disable automatic passivation if idle timeout is set to zero (via config)" in {
      settings("""
        akka.cluster.sharding.passivation.idle.timeout = 0
      """).passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "disable automatic passivation if idle timeout is set to zero (via factory method)" in {
      defaultSettings
        .withIdlePassivationStrategy(Duration.Zero)
        .passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "support old `passivate-idle-entity-after` setting (overriding new strategy settings)" in {
      settings("""
        akka.cluster.sharding {
          passivate-idle-entity-after = 5 minutes
          passivation-strategy = least-recently-used
        }
      """).passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(5.minutes)
    }

  }
}
