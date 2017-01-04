/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

// TODO remove metrics 

import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import com.typesafe.config.ConfigFactory
import akka.testkit.LongRunningTest
import akka.cluster.ClusterEvent._

object ClusterMetricsDisabledMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  commonConfig(ConfigFactory.parseString("akka.cluster.metrics.enabled = off")
    .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterMetricsDisabledMultiJvmNode1 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNode2 extends ClusterMetricsDisabledSpec

abstract class ClusterMetricsDisabledSpec extends MultiNodeSpec(ClusterMetricsDisabledMultiJvmSpec) with MultiNodeClusterSpec {
  "Cluster metrics" must {
    "not collect metrics, not publish ClusterMetricsChanged, and not gossip metrics" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      clusterView.clusterMetrics.size should ===(0)
      cluster.subscribe(testActor, classOf[ClusterMetricsChanged])
      expectMsgType[CurrentClusterState]
      expectNoMsg
      clusterView.clusterMetrics.size should ===(0)
      enterBarrier("after")
    }
  }
}

