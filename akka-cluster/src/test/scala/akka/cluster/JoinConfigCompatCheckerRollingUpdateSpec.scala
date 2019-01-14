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
      akka.cluster.gossip-interval                   = 100 ms
      akka.cluster.leader-actions-interval           = 100 ms
      akka.cluster.unreachable-nodes-reaper-interval = 100 ms
      akka.cluster.periodic-tasks-initial-delay      = 100 ms
      akka.cluster.publish-stats-interval            = 0 s
      failure-detector.heartbeat-interval            = 100 ms
    """)
    .withFallback(JoinConfigCompatCheckerSpec.baseConfig)

  val v1Config: Config = baseConfig.withFallback(JoinConfigCompatCheckerSpec.configWithChecker)

  private val v2 = ConfigFactory.parseString(
    """
      akka.cluster.new-configuration = "v2"
      akka.cluster.configuration-compatibility-check.checkers {
        rolling-upgrade-test = "akka.cluster.JoinConfigCompatRollingUpdateChecker"
      }
    """)

  val v2Config: Config = v2.withFallback(v1Config)

  val v2ConfigIncompatible: Config = v2.withFallback(baseConfig)

}

class JoinConfigCompatCheckerRollingUpdateSpec extends RollingUpgradeClusterSpec(
  JoinConfigCompatCheckerRollingUpdateSpec.v1Config) {

  import JoinConfigCompatCheckerRollingUpdateSpec._

  "A Node" must {
    val timeout = 15.seconds
    "NOT be allowed to re-join a cluster if it has a new, additional configuration the others do not have and not the old" taggedAs LongRunningTest in {
      // confirms the 2 attempted re-joins fail with both nodes being terminated
      upgradeCluster(3, v1Config, v2ConfigIncompatible, timeout, timeout, enforced = true, shouldRejoin = false)
    }
    "be allowed to re-join a cluster if it has a new, additional property and checker the others do not have" taggedAs LongRunningTest in {
      upgradeCluster(3, v1Config, v2Config, timeout, timeout * 3, enforced = true, shouldRejoin = true)
    }
    "be allowed to re-join a cluster if it has a new, additional configuration the others do not have and configured to NOT enforce it" taggedAs LongRunningTest in {
      upgradeCluster(3, v1Config, v2Config, timeout, timeout * 3, enforced = false, shouldRejoin = true)
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
