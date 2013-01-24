/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.concurrent.duration._

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.Address

import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsGossipSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with MetricsCollectorFactory {

  val collector = createMetricsCollector

  "A MetricsGossip" must {
    "add and initialize new NodeMetrics" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      m1.metrics.size must be > (3)
      m2.metrics.size must be > (3)

      val g1 = MetricsGossip.empty :+ m1
      g1.nodes.size must be(1)
      g1.nodeKeys.size must be(g1.nodes.size)
      g1.metricsFor(m1.address).size must be(m1.metrics.size)

      val g2 = g1 :+ m2
      g2.nodes.size must be(2)
      g2.nodeKeys.size must be(g2.nodes.size)
      g2.metricsFor(m1.address).size must be(m1.metrics.size)
      g2.metricsFor(m2.address).size must be(m2.metrics.size)
    }

    "merge peer metrics" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      g1.nodes.size must be(2)
      val beforeMergeNodes = g1.nodes

      val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = m2.timestamp + 1000)
      val g2 = g1 :+ m2Updated // merge peers
      g2.nodes.size must be(2)
      g2.metricsFor(m1.address).size must be(m1.metrics.size)
      g2.metricsFor(m2.address).size must be(m2Updated.metrics.size)
      g2.nodes collect { case peer if peer.address == m2.address ⇒ peer.timestamp must be(m2Updated.timestamp) }
    }

    "merge an existing metric set for a node and update node ring" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)
      val m3 = NodeMetrics(Address("akka", "sys", "a", 2556), newTimestamp, collector.sample.metrics)
      val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = m2.timestamp + 1000)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      val g2 = MetricsGossip.empty :+ m3 :+ m2Updated

      g1.nodeKeys.contains(m1.address) must be(true)
      g2.nodeKeys.contains(m3.address) must be(true)

      // must contain nodes 1,3, and the most recent version of 2
      val mergedGossip = g1 merge g2
      mergedGossip.nodes.size must be(3)
      mergedGossip.metricsFor(m1.address).size must be(m1.metrics.size)
      mergedGossip.metricsFor(m2.address).size must be(m2Updated.metrics.size)
      mergedGossip.metricsFor(m3.address).size must be(m3.metrics.size)
      mergedGossip.nodes.foreach(_.metrics.size must be > (3))
      mergedGossip.nodes.find(_.address == m2.address).get.timestamp must be(m2Updated.timestamp)
    }

    "get the current NodeMetrics if it exists in the local nodes" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val g1 = MetricsGossip.empty :+ m1
      g1.metricsFor(m1.address).nonEmpty must be(true)
    }

    "remove a node if it is no longer Up" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      g1.nodes.size must be(2)
      val g2 = g1 remove m1.address
      g2.nodes.size must be(1)
      g2.nodes.exists(_.address == m1.address) must be(false)
      g2.metricsFor(m1.address).size must be(0)
      g2.metricsFor(m2.address).size must be(m2.metrics.size)
    }
  }
}

