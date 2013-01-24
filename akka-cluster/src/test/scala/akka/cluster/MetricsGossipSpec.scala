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
    "add new NodeMetrics" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      m1.metrics.size must be > (3)
      m2.metrics.size must be > (3)

      val g1 = MetricsGossip.empty :+ m1
      g1.nodes.size must be(1)
      g1.nodeMetricsFor(m1.address).map(_.metrics) must be(Some(m1.metrics))

      val g2 = g1 :+ m2
      g2.nodes.size must be(2)
      g2.nodeMetricsFor(m1.address).map(_.metrics) must be(Some(m1.metrics))
      g2.nodeMetricsFor(m2.address).map(_.metrics) must be(Some(m2.metrics))
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
      g2.nodeMetricsFor(m1.address).map(_.metrics) must be(Some(m1.metrics))
      g2.nodeMetricsFor(m2.address).map(_.metrics) must be(Some(m2Updated.metrics))
      g2.nodes collect { case peer if peer.address == m2.address ⇒ peer.timestamp must be(m2Updated.timestamp) }
    }

    "merge an existing metric set for a node and update node ring" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)
      val m3 = NodeMetrics(Address("akka", "sys", "a", 2556), newTimestamp, collector.sample.metrics)
      val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = m2.timestamp + 1000)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      val g2 = MetricsGossip.empty :+ m3 :+ m2Updated

      g1.nodes.map(_.address) must be(Set(m1.address, m2.address))

      // must contain nodes 1,3, and the most recent version of 2
      val mergedGossip = g1 merge g2
      mergedGossip.nodes.map(_.address) must be(Set(m1.address, m2.address, m3.address))
      mergedGossip.nodeMetricsFor(m1.address).map(_.metrics) must be(Some(m1.metrics))
      mergedGossip.nodeMetricsFor(m2.address).map(_.metrics) must be(Some(m2Updated.metrics))
      mergedGossip.nodeMetricsFor(m3.address).map(_.metrics) must be(Some(m3.metrics))
      mergedGossip.nodes.foreach(_.metrics.size must be > (3))
      mergedGossip.nodeMetricsFor(m2.address).map(_.timestamp) must be(Some(m2Updated.timestamp))
    }

    "get the current NodeMetrics if it exists in the local nodes" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val g1 = MetricsGossip.empty :+ m1
      g1.nodeMetricsFor(m1.address).map(_.metrics) must be(Some(m1.metrics))
    }

    "remove a node if it is no longer Up" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      g1.nodes.size must be(2)
      val g2 = g1 remove m1.address
      g2.nodes.size must be(1)
      g2.nodes.exists(_.address == m1.address) must be(false)
      g2.nodeMetricsFor(m1.address) must be(None)
      g2.nodeMetricsFor(m2.address).map(_.metrics) must be(Some(m2.metrics))
    }

    "filter nodes" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      val g1 = MetricsGossip.empty :+ m1 :+ m2
      g1.nodes.size must be(2)
      val g2 = g1 filter Set(m2.address)
      g2.nodes.size must be(1)
      g2.nodes.exists(_.address == m1.address) must be(false)
      g2.nodeMetricsFor(m1.address) must be(None)
      g2.nodeMetricsFor(m2.address).map(_.metrics) must be(Some(m2.metrics))
    }
  }
}

