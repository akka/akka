/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NodeMetricsSpec extends AkkaSpec with AbstractClusterMetricsSpec with MetricSpec {

  val collector = createMetricsCollector

  val node1 = Address("akka", "sys", "a", 2554)

  val node2 = Address("akka", "sys", "a", 2555)

  "NodeMetrics must" must {
    "recognize updatable nodes" in {
      (NodeMetrics(node1, 0) updatable NodeMetrics(node1, 1)) must be(true)
    }

    "recognize non-updatable nodes" in {
      (NodeMetrics(node1, 1) updatable NodeMetrics(node2, 0)) must be(false)
    }

    "return correct result for 2 'same' nodes" in {
      (NodeMetrics(node1, 0) same NodeMetrics(node1, 0)) must be(true)
    }

    "return correct result for 2 not 'same' nodes" in {
      (NodeMetrics(node1, 0) same NodeMetrics(node2, 0)) must be(false)
    }

    "merge 2 NodeMetrics by most recent" in {
      val sample1 = NodeMetrics(node1, 1, collector.sample.metrics)
      val sample2 = NodeMetrics(node1, 2, collector.sample.metrics)

      val merged = sample1 merge sample2
      merged.timestamp must be(sample2.timestamp)
      merged.metrics must be(sample2.metrics)
    }

    "not merge 2 NodeMetrics if master is more recent" in {
      val sample1 = NodeMetrics(node1, 1, collector.sample.metrics)
      val sample2 = NodeMetrics(node2, 0, sample1.metrics)

      val merged = sample2 merge sample2 // older and not same
      merged.timestamp must be(sample2.timestamp)
      merged.metrics must be(sample2.metrics)
    }
  }
}

