/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics.remote

import akka.cluster._
import akka.actor._
import Actor._
import Cluster._
import akka.dispatch._
import akka.util.Duration
import akka.util.duration._
import akka.cluster.metrics._
import java.util.concurrent._
import atomic.AtomicInteger

object RemoteMetricsMultiJvmSpec {
  val NrOfNodes = 2

  val MetricsRefreshTimeout = 100.millis
}

class AllMetricsAvailableMonitor(_id: String, completionLatch: CountDownLatch, clusterSize: Int) extends ClusterMetricsAlterationMonitor {

  val id = _id

  def reactsOn(allMetrics: Array[NodeMetrics]) = allMetrics.size == clusterSize

  def react(allMetrics: Array[NodeMetrics]) = completionLatch.countDown

}

class RemoteMetricsMultiJvmNode1 extends MasterClusterTestNode {

  import RemoteMetricsMultiJvmSpec._

  val testNodes = NrOfNodes

  "Metrics manager" must {
    "provide metrics of all nodes in the cluster" in {

      val allMetricsAvaiable = new CountDownLatch(1)

      node.metricsManager.refreshTimeout = MetricsRefreshTimeout
      node.metricsManager.addMonitor(new AllMetricsAvailableMonitor("all-metrics-available", allMetricsAvaiable, NrOfNodes))

      LocalCluster.barrier("node-start", NrOfNodes).await()

      allMetricsAvaiable.await()

      LocalCluster.barrier("check-all-remote-metrics", NrOfNodes) {
        node.metricsManager.getAllMetrics.size must be(2)
      }

      val cachedMetrics = node.metricsManager.getMetrics("node2")
      val metricsFromZnode = node.metricsManager.getMetrics("node2", false)

      LocalCluster.barrier("check-single-remote-metrics", NrOfNodes) {
        cachedMetrics must not be (null)
        metricsFromZnode must not be (null)
      }

      Thread sleep MetricsRefreshTimeout.toMillis

      LocalCluster.barrier("remote-metrics-is-updated", NrOfNodes) {
        node.metricsManager.getMetrics("node2") must not be (cachedMetrics)
        node.metricsManager.getMetrics("node2", false) must not be (metricsFromZnode)
      }

      val someMetricsGone = new CountDownLatch(1)
      node.metricsManager.addMonitor(new AllMetricsAvailableMonitor("some-metrics-gone", someMetricsGone, 1))

      LocalCluster.barrier("some-nodes-leave", NrOfNodes).await()

      someMetricsGone.await(10, TimeUnit.SECONDS) must be(true)

      node.metricsManager.getMetrics("node2") must be(None)
      node.metricsManager.getMetrics("node2", false) must be(None)
      node.metricsManager.getAllMetrics.size must be(1)

      node.shutdown()

    }
  }

}

class RemoteMetricsMultiJvmNode2 extends ClusterTestNode {

  import RemoteMetricsMultiJvmSpec._

  val testNodes = NrOfNodes

  "Metrics manager" must {
    "provide metrics of all nodes in the cluster" in {

      val allMetricsAvaiable = new CountDownLatch(1)

      node.metricsManager.refreshTimeout = MetricsRefreshTimeout
      node.metricsManager.addMonitor(new AllMetricsAvailableMonitor("all-metrics-available", allMetricsAvaiable, NrOfNodes))

      LocalCluster.barrier("node-start", NrOfNodes).await()

      allMetricsAvaiable.await()

      LocalCluster.barrier("check-all-remote-metrics", NrOfNodes) {
        node.metricsManager.getAllMetrics.size must be(2)
      }

      val cachedMetrics = node.metricsManager.getMetrics("node1")
      val metricsFromZnode = node.metricsManager.getMetrics("node1", false)

      LocalCluster.barrier("check-single-remote-metrics", NrOfNodes) {
        cachedMetrics must not be (null)
        metricsFromZnode must not be (null)
      }

      Thread sleep MetricsRefreshTimeout.toMillis

      LocalCluster.barrier("remote-metrics-is-updated", NrOfNodes) {
        node.metricsManager.getMetrics("node1") must not be (cachedMetrics)
        node.metricsManager.getMetrics("node1", false) must not be (metricsFromZnode)
      }

      LocalCluster.barrier("some-nodes-leave", NrOfNodes) {
        node.shutdown()
      }
    }
  }

}

