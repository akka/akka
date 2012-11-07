/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.concurrent.duration._

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.Address

import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsGossipSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with MetricSpec
  with MetricsCollectorFactory {

  val collector = createMetricsCollector

  "A MetricsGossip" must {
    "add and initialize new NodeMetrics" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      var localGossip = MetricsGossip()
      localGossip :+= m1
      localGossip.nodes.size must be(1)
      localGossip.nodeKeys.size must be(localGossip.nodes.size)
      assertMasterMetricsAgainstGossipMetrics(Set(m1), localGossip)
      collector.sample.metrics.size must be > (3)

      localGossip :+= m2
      localGossip.nodes.size must be(2)
      localGossip.nodeKeys.size must be(localGossip.nodes.size)
      assertMasterMetricsAgainstGossipMetrics(Set(m1, m2), localGossip)
      collector.sample.metrics.size must be > (3)
    }

    "merge peer metrics" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      var remoteGossip = MetricsGossip()
      remoteGossip :+= m1
      remoteGossip :+= m2
      remoteGossip.nodes.size must be(2)
      val beforeMergeNodes = remoteGossip.nodes

      val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = newTimestamp)
      remoteGossip :+= m2Updated // merge peers
      remoteGossip.nodes.size must be(2)
      assertMasterMetricsAgainstGossipMetrics(beforeMergeNodes, remoteGossip)
      remoteGossip.nodes.foreach(_.metrics.size must be > (3))
      remoteGossip.nodes collect { case peer if peer.address == m2.address ⇒ peer.timestamp must be(m2Updated.timestamp) }
    }

    "merge an existing metric set for a node and update node ring" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)
      val m3 = NodeMetrics(Address("akka", "sys", "a", 2556), newTimestamp, collector.sample.metrics)
      val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = newTimestamp)

      var localGossip = MetricsGossip()
      localGossip :+= m1
      localGossip :+= m2

      var remoteGossip = MetricsGossip()
      remoteGossip :+= m3
      remoteGossip :+= m2Updated

      localGossip.nodeKeys.contains(m1.address) must be(true)
      remoteGossip.nodeKeys.contains(m3.address) must be(true)

      // must contain nodes 1,3, and the most recent version of 2
      val mergedGossip = localGossip merge remoteGossip
      mergedGossip.nodes.size must be(3)
      assertExpectedNodeAddresses(mergedGossip, Set(m1, m2, m3))
      mergedGossip.nodes.foreach(_.metrics.size must be > (3))
      mergedGossip.nodes.find(_.address == m2.address).get.timestamp must be(m2Updated.timestamp)
    }

    "get the current NodeMetrics if it exists in the local nodes" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      var localGossip = MetricsGossip()
      localGossip :+= m1
      localGossip.metricsFor(m1).nonEmpty must be(true)
    }

    "remove a node if it is no longer Up" in {
      val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)

      var localGossip = MetricsGossip()
      localGossip :+= m1
      localGossip :+= m2

      localGossip.nodes.size must be(2)
      localGossip = localGossip remove m1.address
      localGossip.nodes.size must be(1)
      localGossip.nodes.exists(_.address == m1.address) must be(false)
    }
  }
}

