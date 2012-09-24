/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.util.duration._
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

  commonConfig(ConfigFactory.parseString("""
    akka.cluster.auto-join = on
    akka.cluster.metrics.enabled = on
    akka.cluster.metrics.metrics-interval = 3 s
    akka.cluster.metrics.gossip-interval = 3 s
    akka.cluster.metrics.rate-of-decay = 10
    akka.loglevel = INFO
    akka.remote.log-sent-messages = off
    akka.remote.log-received-messages = off
    akka.actor.debug.receive = off
    akka.actor.debug.unhandled = off
    akka.actor.debug.lifecycle = off
    akka.actor.debug.autoreceive = off
    akka.actor.debug.fsm = off""")
    .withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class ClusterMetricsMultiJvmNode1 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode2 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode3 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode4 extends ClusterMetricsSpec
class ClusterMetricsMultiJvmNode5 extends ClusterMetricsSpec

abstract class ClusterMetricsSpec extends MultiNodeSpec(ClusterMetricsMultiJvmSpec) with MultiNodeClusterSpec with MetricSpec {
  import ClusterMetricsMultiJvmSpec._

  val collector = MetricsCollector(cluster.selfAddress, log, system.asInstanceOf[ExtendedActorSystem].dynamicAccess)

  "Cluster metrics" must {
    "periodically collect metrics on each node, publish ClusterMetricsChanged to the event stream, " +
      "and gossip metrics around the node ring" taggedAs LongRunningTest in within(60 seconds) {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-started")
      runOn(roles: _*) {
        awaitCond(clusterView.members.filter(_.status == MemberStatus.Up).size == roles.size)
        awaitCond(clusterView.clusterMetrics.size == roles.size)
        assertInitialized(cluster.settings.MetricsRateOfDecay, collectNodeMetrics(clusterView.clusterMetrics).toSet)
        clusterView.clusterMetrics.foreach(n => assertExpectedSampleSize(collector.isSigar, cluster.settings.MetricsRateOfDecay, n))
      }
      enterBarrier("after")
    }
    "reflect the correct number of node metrics in cluster view" taggedAs LongRunningTest in within(40 seconds) {
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