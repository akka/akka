/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.ExtendedActorSystem

object ClusterMetricsMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterMetricsMultiJvmNode1 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode2 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode3 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode4 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode5 extends ClusterMetricsSpec

abstract class ClusterMetricsSpec extends MultiNodeSpec(ClusterMetricsMultiJvmSpec) with MultiNodeClusterSpec with MetricSpec {
  import ClusterMetricsMultiJvmSpec._

  def isSigar(collector: MetricsCollector): Boolean = collector.isInstanceOf[SigarMetricsCollector]

  "Cluster metrics" must {
    "periodically collect metrics on each node, publish ClusterMetricsChanged to the event stream, " +
      "and gossip metrics around the node ring" taggedAs LongRunningTest in within(60 seconds) {
        awaitClusterUp(roles: _*)
        enterBarrier("cluster-started")
        awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
        awaitCond(clusterView.clusterMetrics.size == roles.size)
        val collector = MetricsCollector(cluster.system, cluster.settings)
        collector.sample.metrics.size must be > (3)
        enterBarrier("after")
      }
    "reflect the correct number of node metrics in cluster view" taggedAs LongRunningTest in within(30 seconds) {
      runOn(second) {
        cluster.leave(first)
      }
      enterBarrier("first-left")
      runOn(second, third, fourth, fifth) {
        awaitCond(clusterView.clusterMetrics.size == (roles.size - 1))
      }
      enterBarrier("finished")
    }
  }
}