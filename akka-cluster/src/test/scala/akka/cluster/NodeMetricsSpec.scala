/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

// TODO remove metrics

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address

class NodeMetricsSpec extends WordSpec with Matchers {

  val node1 = Address("akka.tcp", "sys", "a", 2554)
  val node2 = Address("akka.tcp", "sys", "a", 2555)

  "NodeMetrics must" must {

    "return correct result for 2 'same' nodes" in {
      (NodeMetrics(node1, 0) sameAs NodeMetrics(node1, 0)) should ===(true)
    }

    "return correct result for 2 not 'same' nodes" in {
      (NodeMetrics(node1, 0) sameAs NodeMetrics(node2, 0)) should ===(false)
    }

    "merge 2 NodeMetrics by most recent" in {
      val sample1 = NodeMetrics(node1, 1, Set(Metric.create("a", 10, None), Metric.create("b", 20, None)).flatten)
      val sample2 = NodeMetrics(node1, 2, Set(Metric.create("a", 11, None), Metric.create("c", 30, None)).flatten)

      val merged = sample1 merge sample2
      merged.timestamp should ===(sample2.timestamp)
      merged.metric("a").map(_.value) should ===(Some(11))
      merged.metric("b").map(_.value) should ===(Some(20))
      merged.metric("c").map(_.value) should ===(Some(30))
    }

    "not merge 2 NodeMetrics if master is more recent" in {
      val sample1 = NodeMetrics(node1, 1, Set(Metric.create("a", 10, None), Metric.create("b", 20, None)).flatten)
      val sample2 = NodeMetrics(node1, 0, Set(Metric.create("a", 11, None), Metric.create("c", 30, None)).flatten)

      val merged = sample1 merge sample2 // older and not same
      merged.timestamp should ===(sample1.timestamp)
      merged.metrics should ===(sample1.metrics)
    }
  }
}

