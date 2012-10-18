/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import akka.actor.Address
import akka.cluster.NodeMetrics.MetricValues
import util.control.NonFatal
import util.Try

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsAwareClusterNodeSelectorSpec extends ClusterMetricsRouteeSelectorSpec with MetricsAwareClusterNodeSelector {
  import NodeMetrics.NodeMetricsComparator.longMinAddressOrdering

  "MetricsAwareClusterNodeSelector" must {

    "select the address of the node with the lowest memory" in {
      for (i ← 0 to 10) { // run enough times to insure we test differing metric values

        val map = nodes.map(n ⇒ n.address -> MetricValues.unapply(n.heapMemory)).toMap
        val (used1, committed1, max1) = map.get(node1.address).get
        val (used2, committed2, max2) = map.get(node2.address).get
        val diff1 = max1 match {
          case Some(m) ⇒ ((committed1 - used1) + (m - used1) + (m - committed1))
          case None    ⇒ committed1 - used1
        }
        val diff2 = max2 match {
          case Some(m) ⇒ ((committed2 - used2) + (m - used2) + (m - committed2))
          case None    ⇒ committed2 - used2
        }
        val testMin = Set(diff1, diff2).min
        val expectedAddress = if (testMin == diff1) node1.address else node2.address
        val address = selectByMemory(nodes.toSet).get
        address must be(expectedAddress)
      }
    }
    "select the address of the node with the lowest network latency" in {
      // TODO
    }
    "select the address of the node with the best CPU health" in {
      // TODO
    }
    "select the address of the node with the best overall health based on all metric categories monitored" in {
      // TODO
    }
  }
}

abstract class ClusterMetricsRouteeSelectorSpec extends AkkaSpec(MetricsEnabledSpec.config) with AbstractClusterMetricsSpec {

  val collector = createMetricsCollector

  var node1 = NodeMetrics(Address("akka", "sys", "a", 2554), 1, collector.sample.metrics)
  node1 = node1.copy(metrics = node1.metrics.collect { case m ⇒ m.initialize(DefaultRateOfDecay) })

  var node2 = NodeMetrics(Address("akka", "sys", "a", 2555), 1, collector.sample.metrics)
  node2 = node2.copy(metrics = node2.metrics.collect { case m ⇒ m.initialize(DefaultRateOfDecay) })

  var nodes: Seq[NodeMetrics] = Seq(node1, node2)

  // work up the data streams where applicable
  for (i ← 0 to samples) {
    nodes = nodes map {
      n ⇒
        n.copy(metrics = collector.sample.metrics.flatMap(latest ⇒ n.metrics.collect {
          case streaming if latest same streaming ⇒
            streaming.average match {
              case Some(e) ⇒ streaming.copy(value = latest.value, average = Try(Some(e :+ latest.value.get)) getOrElse None)
              case None    ⇒ streaming.copy(value = latest.value)
            }
        }))
    }
  }
}
