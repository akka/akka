/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.util.duration._
import akka.remote.testkit.{MultiNodeSpec, MultiNodeConfig}
import com.typesafe.config.ConfigFactory
import akka.testkit.LongRunningTest


object ClusterMetricsDataStreamingOffMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  commonConfig(ConfigFactory.parseString("akka.cluster.metrics.rate-of-decay = 0")
    .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}
class ClusterMetricsDataStreamingMultiJvmNode1 extends ClusterMetricsDataStreamingOffSpec
class ClusterMetricsDataStreamingMultiJvmNode2 extends ClusterMetricsDataStreamingOffSpec

abstract class ClusterMetricsDataStreamingOffSpec extends MultiNodeSpec(ClusterMetricsDataStreamingOffMultiJvmSpec) with MultiNodeClusterSpec with MetricSpec {
  "Cluster metrics" must {
    "not collect stream metric data" taggedAs LongRunningTest in within(30 seconds) {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      runOn(roles: _*) {
        awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
        awaitCond(clusterView.clusterMetrics.size == roles.size)
        awaitCond(clusterView.clusterMetrics.flatMap(_.metrics).filter(_.trendable).forall(_.average.isEmpty))
      }
      enterBarrier("after")
    }
  }
}
