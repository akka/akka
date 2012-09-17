/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
import com.typesafe.config.ConfigFactory
import akka.testkit.LongRunningTest

object ClusterMetricsDisabledMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  commonConfig(ConfigFactory.parseString("""akka.cluster.metrics.enabled = off""")
    .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterMetricsDisabledMultiJvmNode1 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNode2 extends ClusterMetricsDisabledSpec

abstract class ClusterMetricsDisabledSpec extends MultiNodeSpec(ClusterMetricsDisabledMultiJvmSpec) with MultiNodeClusterSpec {
  "Cluster metrics" must {
    "not collect metrics, not publish ClusterMetricsChanged, and not gossip metrics" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      runOn(roles: _*) {
        awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
        awaitCond(clusterView.clusterMetrics.size == 0)
      }
      enterBarrier("after")
    }
  }
}

