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
      defaultSettings.passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 120.seconds,
        interval = 60.seconds)
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
      """).passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 3.minutes,
        interval = 90.seconds)
    }

    "allow timeout for (default) idle passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withIdlePassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.IdleSettings.defaults.withTimeout(42.seconds))
        .passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 42.seconds,
        interval = 21.seconds)
    }

    "allow timeout and interval for (default) idle passivation strategy to be configured (via config)" in {
      settings("""
        akka.cluster.sharding {
          passivation {
            idle {
              timeout = 3 minutes
              interval = 1 minute
            }
          }
        }
      """).passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 3.minutes,
        interval = 1.minute)
    }

    "allow timeout and interval for (default) idle passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withIdlePassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.IdleSettings.defaults
            .withTimeout(42.seconds)
            .withInterval(42.millis))
        .passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 42.seconds,
        interval = 42.millis)
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
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = None)
    }

    "allow least recently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withLeastRecentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.LeastRecentlyUsedSettings.defaults.withLimit(42000))
        .passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(
        limit = 42000,
        idle = None)
    }

    "allow least recently used passivation strategy with idle timeout to be configured (via config)" in {
      settings("""
        #passivation-least-recently-used-with-idle
        akka.cluster.sharding {
          passivation {
            strategy = least-recently-used
            least-recently-used {
              limit = 1000000
              idle.timeout = 30.minutes
            }
          }
        }
        #passivation-least-recently-used-with-idle
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 30.minutes, interval = 15.minutes)))
    }

    "allow least recently used passivation strategy with idle timeout to be configured (via factory method)" in {
      defaultSettings
        .withLeastRecentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.LeastRecentlyUsedSettings.defaults
            .withLimit(42000)
            .withIdle(timeout = 42.minutes))
        .passivationStrategy shouldBe ClusterShardingSettings.LeastRecentlyUsedPassivationStrategy(
        limit = 42000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 42.minutes, interval = 21.minutes)))
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
      """).passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = None)
    }

    "allow most recently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withMostRecentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.MostRecentlyUsedSettings.defaults.withLimit(42000))
        .passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(
        limit = 42000,
        idle = None)
    }

    "allow most recently used passivation strategy with idle timeout to be configured (via config)" in {
      settings("""
        #passivation-most-recently-used-with-idle
        akka.cluster.sharding {
          passivation {
            strategy = most-recently-used
            most-recently-used {
              limit = 1000000
              idle.timeout = 30.minutes
            }
          }
        }
        #passivation-most-recently-used-with-idle
      """).passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 30.minutes, interval = 15.minutes)))
    }

    "allow most recently used passivation strategy with idle timeout to be configured (via factory method)" in {
      defaultSettings
        .withMostRecentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.MostRecentlyUsedSettings.defaults
            .withLimit(42000)
            .withIdle(timeout = 42.minutes))
        .passivationStrategy shouldBe ClusterShardingSettings.MostRecentlyUsedPassivationStrategy(
        limit = 42000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 42.minutes, interval = 21.minutes)))
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
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = None)
    }

    "allow least frequently used passivation strategy to be configured (via factory method)" in {
      defaultSettings
        .withLeastFrequentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.LeastFrequentlyUsedSettings.defaults.withLimit(42000))
        .passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(
        limit = 42000,
        idle = None)
    }

    "allow least frequently used passivation strategy with idle timeout to be configured (via config)" in {
      settings("""
        #passivation-least-frequently-used-with-idle
        akka.cluster.sharding {
          passivation {
            strategy = least-frequently-used
            least-frequently-used {
              limit = 1000000
              idle.timeout = 30.minutes
            }
          }
        }
        #passivation-least-frequently-used-with-idle
      """).passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(
        limit = 1000000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 30.minutes, interval = 15.minutes)))
    }

    "allow least frequently used passivation strategy with idle timeout to be configured (via factory method)" in {
      defaultSettings
        .withLeastFrequentlyUsedPassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.LeastFrequentlyUsedSettings.defaults
            .withLimit(42000)
            .withIdle(timeout = 42.minutes))
        .passivationStrategy shouldBe ClusterShardingSettings.LeastFrequentlyUsedPassivationStrategy(
        limit = 42000,
        idle = Some(ClusterShardingSettings.IdlePassivationStrategy(timeout = 42.minutes, interval = 21.minutes)))
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
        .withIdlePassivationStrategy(
          ClusterShardingSettings.PassivationStrategySettings.IdleSettings.defaults.withTimeout(Duration.Zero))
        .passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "disable automatic passivation if disabled (via factory method)" in {
      defaultSettings
        .withNoPassivationStrategy()
        .passivationStrategy shouldBe ClusterShardingSettings.NoPassivationStrategy
    }

    "support old `passivate-idle-entity-after` setting (overriding new strategy settings)" in {
      settings("""
        akka.cluster.sharding {
          passivate-idle-entity-after = 5 minutes
          passivation-strategy = least-recently-used
        }
      """).passivationStrategy shouldBe ClusterShardingSettings.IdlePassivationStrategy(
        timeout = 5.minutes,
        interval = 2.5.minutes)
    }

  }
}
