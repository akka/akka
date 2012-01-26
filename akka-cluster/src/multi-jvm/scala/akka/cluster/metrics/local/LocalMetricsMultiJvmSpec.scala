/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics.local

import akka.cluster._
import akka.actor._
import Actor._
import Cluster._
import akka.dispatch._
import akka.util.Duration
import akka.util.duration._
import akka.cluster.metrics._
import java.util.concurrent.atomic.AtomicInteger

object LocalMetricsMultiJvmSpec {
  val NrOfNodes = 1
}

class LocalMetricsMultiJvmNode1 extends MasterClusterTestNode {

  import LocalMetricsMultiJvmSpec._

  val testNodes = NrOfNodes

  override def beforeAll = {
    super.beforeAll()
    node
  }

  override def afterAll = {
    node.shutdown()
    super.afterAll()
  }

  "Metrics manager" must {

    def timeout = node.metricsManager.refreshTimeout

    "be initialized with refresh timeout value, specified in akka.conf" in {
      timeout must be(1.second)
    }

    "return up-to-date local node metrics straight from MBeans/Sigar" in {
      node.metricsManager.getLocalMetrics must not be (null)

      node.metricsManager.getLocalMetrics.systemLoadAverage must be(0.5 plusOrMinus 0.5)
    }

    "return metrics cached in the MetricsManagerLocalMetrics" in {
      node.metricsManager.getMetrics(nodeAddress.nodeName) must not be (null)
    }

    "return local node metrics from ZNode" in {
      node.metricsManager.getMetrics(nodeAddress.nodeName, false) must not be (null)
    }

    "return cached metrics of all nodes in the cluster" in {
      node.metricsManager.getAllMetrics.size must be(1)
      node.metricsManager.getAllMetrics.find(_.nodeName == "node1") must not be (null)
    }

    "throw no exceptions, when user attempts to get metrics of a non-existing node" in {
      node.metricsManager.getMetrics("nonexisting") must be(None)
      node.metricsManager.getMetrics("nonexisting", false) must be(None)
    }

    "regularly update cached metrics" in {
      val oldMetrics = node.metricsManager.getLocalMetrics
      Thread sleep timeout.toMillis
      node.metricsManager.getLocalMetrics must not be (oldMetrics)
    }

    "allow to track JVM state and bind handles through MetricsAlterationMonitors" in {
      val monitorReponse = Promise[String]()

      node.metricsManager.addMonitor(new LocalMetricsAlterationMonitor {

        val id = "heapMemoryThresholdMonitor"

        def reactsOn(metrics: NodeMetrics) = metrics.usedHeapMemory > 1

        def react(metrics: NodeMetrics) = monitorReponse.success("Too much memory is used!")

      })

      Await.result(monitorReponse, 5 seconds) must be("Too much memory is used!")

    }

    class FooMonitor(monitorWorked: AtomicInteger) extends LocalMetricsAlterationMonitor {
      val id = "fooMonitor"
      def reactsOn(metrics: NodeMetrics) = true
      def react(metrics: NodeMetrics) = monitorWorked.set(monitorWorked.get + 1)
    }

    "allow to unregister the monitor" in {

      val monitorWorked = new AtomicInteger(0)
      val fooMonitor = new FooMonitor(monitorWorked)

      node.metricsManager.addMonitor(fooMonitor)
      node.metricsManager.removeMonitor(fooMonitor)

      val oldValue = monitorWorked.get
      Thread sleep timeout.toMillis
      monitorWorked.get must be(oldValue)

    }

    "stop notifying monitors, when stopped" in {

      node.metricsManager.stop()

      val monitorWorked = new AtomicInteger(0)

      node.metricsManager.addMonitor(new LocalMetricsAlterationMonitor {
        val id = "fooMonitor"
        def reactsOn(metrics: NodeMetrics) = true
        def react(metrics: NodeMetrics) = monitorWorked.set(monitorWorked.get + 1)
      })

      monitorWorked.get must be(0)

      node.metricsManager.start()
      Thread sleep (timeout.toMillis * 2)
      monitorWorked.get must be > (1)

    }

  }

}
