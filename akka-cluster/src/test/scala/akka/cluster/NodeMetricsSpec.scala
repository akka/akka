/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NodeMetricsSpec extends WordSpec with MustMatchers {

  val node1 = Address("akka", "sys", "a", 2554)
  val node2 = Address("akka", "sys", "a", 2555)

  "NodeMetrics must" must {

    "return correct result for 2 'same' nodes" in {
      (NodeMetrics(node1, 0) sameAs NodeMetrics(node1, 0)) must be(true)
    }

    "return correct result for 2 not 'same' nodes" in {
      (NodeMetrics(node1, 0) sameAs NodeMetrics(node2, 0)) must be(false)
    }

    "merge 2 NodeMetrics by most recent" in {
      val sample1 = NodeMetrics(node1, 1, Set(Metric.create("a", 10, None), Metric.create("b", 20, None)).flatten)
      val sample2 = NodeMetrics(node1, 2, Set(Metric.create("a", 11, None), Metric.create("c", 30, None)).flatten)

      val merged = sample1 merge sample2
      merged.timestamp must be(sample2.timestamp)
      merged.metric("a").map(_.value) must be(Some(11))
      merged.metric("b").map(_.value) must be(Some(20))
      merged.metric("c").map(_.value) must be(Some(30))
    }

    "not merge 2 NodeMetrics if master is more recent" in {
      val sample1 = NodeMetrics(node1, 1, Set(Metric.create("a", 10, None), Metric.create("b", 20, None)).flatten)
      val sample2 = NodeMetrics(node1, 0, Set(Metric.create("a", 11, None), Metric.create("c", 30, None)).flatten)

      val merged = sample1 merge sample2 // older and not same
      merged.timestamp must be(sample1.timestamp)
      merged.metrics must be(sample1.metrics)
    }
  }
}

