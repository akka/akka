/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration._
import scala.collection.{ immutable ⇒ im }

import akka.testkit.LongRunningTest
import com.typesafe.config.{ Config, ConfigFactory }

object JoinConfigCompatCheckerRollingUpdateSpec {

  val baseConfig = ConfigFactory.parseString(
    s"""
      akka.log-dead-letters = off
      akka.log-dead-letters-during-shutdown = off
      akka.remote.log-remote-lifecycle-events = off
      akka.cluster.jmx.enabled = off
    """)
    .withFallback(JoinConfigCompatCheckerSpec.baseConfig)

  val v1Config: Config = baseConfig.withFallback(JoinConfigCompatCheckerSpec.configWithChecker)

  val v2Config: Config = ConfigFactory.parseString(
    """
      akka.cluster.new-configuration = "v2"
      akka.cluster.configuration-compatibility-check.checkers {
        rolling-upgrade-test = "akka.cluster.JoinConfigCompatRollingUpdateChecker"
      }
    """).withFallback(v1Config)

  val v2ConfigIncompatible: Config = ConfigFactory.parseString(
    """
      akka.cluster.new-configuration = "v2"
      akka.cluster.configuration-compatibility-check.checkers {
        rolling-upgrade-test = "akka.cluster.JoinConfigCompatRollingUpdateChecker"
      }
    """).withFallback(baseConfig)

}

class JoinConfigCompatCheckerRollingUpdateSpec extends RollingUpgradeClusterSpec(
  JoinConfigCompatCheckerRollingUpdateSpec.v1Config) {

  import JoinConfigCompatCheckerRollingUpdateSpec._

  "A Node" must {
    "not be allowed to re-join a cluster if it has a new, additional configuration the others do not have and not the old" taggedAs LongRunningTest in {
      intercept[AssertionError] {
        upgradeCluster(3, v1Config, v2ConfigIncompatible, timeout = 20.seconds, enforced = true)
      }
    }
    "be allowed to re-join a cluster if it has a new, additional property and checker the others do not have" taggedAs LongRunningTest in {
      upgradeCluster(3, v1Config, v2Config, timeout = 20.seconds, enforced = true)
    }
    "be allowed to re-join a cluster if it has a new, additional configuration the others do not have and configured to NOT enforce it" taggedAs LongRunningTest in {
      upgradeCluster(3, v1Config, v2Config, timeout = 20.seconds, enforced = false)
    }
  }
}

class JoinConfigCompatRollingUpdateChecker extends JoinConfigCompatChecker {
  override def requiredKeys: im.Seq[String] = im.Seq("akka.cluster.new-configuration")
  override def check(toCheck: Config, actualConfig: Config): ConfigValidation = {
    if (toCheck.hasPath(requiredKeys.head))
      JoinConfigCompatChecker.fullMatch(requiredKeys, toCheck, actualConfig)
    else Valid
  }
}
