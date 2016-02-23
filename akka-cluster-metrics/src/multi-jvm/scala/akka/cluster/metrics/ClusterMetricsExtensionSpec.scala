/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.metrics

import scala.language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.actor.ExtendedActorSystem
import akka.cluster.MultiNodeClusterSpec
import akka.testkit.LongRunningTest
import akka.cluster.MemberStatus

trait ClusterMetricsCommonConfig extends MultiNodeConfig {
  import ConfigFactory._

  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")
  val node4 = role("node-4")
  val node5 = role("node-5")

  def nodeList = Seq(node1, node2, node3, node4, node5)

  // Extract individual sigar library for every node.
  nodeList foreach { role ⇒
    nodeConfig(role) {
      parseString("akka.cluster.metrics.native-library-extract-folder=${user.dir}/target/native/" + role.name)
    }
  }

  // Disable legacy metrics in akka-cluster.
  def disableMetricsLegacy = parseString("""akka.cluster.metrics.enabled=off""")

  // Enable metrics extension in akka-cluster-metrics.
  def enableMetricsExtension = parseString("""
    akka.extensions=["akka.cluster.metrics.ClusterMetricsExtension"]
    akka.cluster.metrics.collector.enabled = on
    """)

  // Disable metrics extension in akka-cluster-metrics.
  def disableMetricsExtension = parseString("""
    akka.extensions=["akka.cluster.metrics.ClusterMetricsExtension"]
    akka.cluster.metrics.collector.enabled = off
    """)

  // Activate slf4j logging along with test listener.
  def customLogging = parseString("""akka.loggers=["akka.testkit.TestEventListener","akka.event.slf4j.Slf4jLogger"]""")
}

object ClusterMetricsDisabledConfig extends ClusterMetricsCommonConfig {

  commonConfig {
    Seq(
      customLogging,
      disableMetricsLegacy,
      disableMetricsExtension,
      debugConfig(on = false),
      MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet)
      .reduceLeft(_ withFallback _)
  }
}

object ClusterMetricsEnabledConfig extends ClusterMetricsCommonConfig {
  import ConfigFactory._

  commonConfig {
    Seq(
      customLogging,
      disableMetricsLegacy,
      enableMetricsExtension,
      debugConfig(on = false),
      MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet)
      .reduceLeft(_ withFallback _)
  }

}

class ClusterMetricsEnabledMultiJvmNode1 extends ClusterMetricsEnabledSpec
class ClusterMetricsEnabledMultiJvmNode2 extends ClusterMetricsEnabledSpec
class ClusterMetricsEnabledMultiJvmNode3 extends ClusterMetricsEnabledSpec
class ClusterMetricsEnabledMultiJvmNode4 extends ClusterMetricsEnabledSpec
class ClusterMetricsEnabledMultiJvmNode5 extends ClusterMetricsEnabledSpec

abstract class ClusterMetricsEnabledSpec extends MultiNodeSpec(ClusterMetricsEnabledConfig)
  with MultiNodeClusterSpec with RedirectLogging {
  import ClusterMetricsEnabledConfig._

  def isSigar(collector: MetricsCollector): Boolean = collector.isInstanceOf[SigarMetricsCollector]

  def saveApplicationConf(): Unit = {
    import java.io.File
    import java.io.PrintWriter
    val conf = cluster.system.settings.config
    val text = conf.root.render
    val file = new File(s"target/${myself.name}_application.conf")
    Some(new PrintWriter(file)) map { p ⇒ p.write(text); p.close }
  }

  saveApplicationConf()

  val metricsView = new ClusterMetricsView(cluster.system)

  "Cluster metrics" must {
    "periodically collect metrics on each node, publish to the event stream, " +
      "and gossip metrics around the node ring" in within(60 seconds) {
        awaitClusterUp(roles: _*)
        enterBarrier("cluster-started")
        awaitAssert(clusterView.members.count(_.status == MemberStatus.Up) should ===(roles.size))
        // TODO ensure same contract
        //awaitAssert(clusterView.clusterMetrics.size should ===(roles.size))
        awaitAssert(metricsView.clusterMetrics.size should ===(roles.size))
        val collector = MetricsCollector(cluster.system)
        collector.sample.metrics.size should be > (3)
        enterBarrier("after")
      }
    "reflect the correct number of node metrics in cluster view" in within(30 seconds) {
      runOn(node2) {
        cluster.leave(node1)
      }
      enterBarrier("first-left")
      runOn(node2, node3, node4, node5) {
        markNodeAsUnavailable(node1)
        // TODO ensure same contract
        //awaitAssert(clusterView.clusterMetrics.size should ===(roles.size - 1))
        awaitAssert(metricsView.clusterMetrics.size should ===(roles.size - 1))
      }
      enterBarrier("finished")
    }
  }
}

class ClusterMetricsDisabledMultiJvmNode1 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNode2 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNodv3 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNode4 extends ClusterMetricsDisabledSpec
class ClusterMetricsDisabledMultiJvmNode5 extends ClusterMetricsDisabledSpec

abstract class ClusterMetricsDisabledSpec extends MultiNodeSpec(ClusterMetricsDisabledConfig)
  with MultiNodeClusterSpec with RedirectLogging {
  import akka.cluster.ClusterEvent.CurrentClusterState

  val metricsView = new ClusterMetricsView(cluster.system)

  "Cluster metrics" must {
    "not collect metrics, not publish metrics events, and not gossip metrics" in {
      awaitClusterUp(roles: _*)
      // TODO ensure same contract
      //clusterView.clusterMetrics.size should ===(0)
      metricsView.clusterMetrics.size should ===(0)
      ClusterMetricsExtension(system).subscribe(testActor)
      expectNoMsg
      // TODO ensure same contract
      //clusterView.clusterMetrics.size should ===(0)
      metricsView.clusterMetrics.size should ===(0)
      enterBarrier("after")
    }
  }
}
