/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.Address
import akka.cluster.MemberStatus.{ Down, Joining, Up }
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import System.{ currentTimeMillis ⇒ newTimestamp }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsGossipSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractMetricsCollectorSpec {

  val node1 = Member(Address("akka", "sys", "a", 2554), Down)
  val node2 = Member(Address("akka", "sys", "a", 2555), Joining)
  val node3 = Member(Address("akka", "sys", "a", 2556), Joining)

  val collector = createMetricsCollector

  "A MetricsGossip" must {
    "successfully add and initialize new NodeMetrics and merge existing, by most recent Metric timestamps, from a remote gossip" in {

      val m1 = NodeMetrics(node1.address, newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(node2.address, newTimestamp, collector.sample.metrics)
      val m3 = NodeMetrics(node3.address, newTimestamp, collector.sample.metrics)

      var gossip1 = MetricsGossip(window)
      var gossip2 = MetricsGossip(window)

      gossip1 = gossip1 :+ m1
      gossip1 = gossip1 :+ m2
      gossip2 = gossip2 :+ m3
      val updatedM2 = m2.copy(metrics = collector.sample.metrics, timestamp = newTimestamp)
      gossip2 = gossip2 :+ updatedM2
      gossip2.nodes.size must be(2)

      // must contain nodes 1,3, and the most recent version of m2
      val mergedGossip = gossip1 merge gossip2
      mergedGossip.nodes.size must be(3)

      mergedGossip.nodes.exists(_.address == m1.address) must be(true)
      mergedGossip.nodes.exists(_.address == m2.address) must be(true)
      mergedGossip.nodes.exists(_.address == m3.address) must be(true)
      val merged = mergedGossip.nodes.filter(_.address == m2.address).head
      merged.timestamp must be(updatedM2.timestamp)
    }

    "successfully merge a existing metric set for a node and update node ring" in {
      val m1 = NodeMetrics(node1.address, newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(node2.address, newTimestamp, collector.sample.metrics)
      var gossip = MetricsGossip(window)
      gossip = gossip :+ m1
      gossip = gossip :+ m2
      gossip.nodes.size must be(2)

      gossip = gossip :+ m1.copy(metrics = collector.sample.metrics, timestamp = newTimestamp)
      gossip.nodes.size must be(2)

      val nodeMap = gossip.nodes.map(n ⇒ n.address -> n).toMap
      val m1Updated = nodeMap.get(m1.address).get
      val sla = m1Updated.metrics.filter(_.name == "heap-memory-committed").head
      sla.average.isDefined must be(true)
    }

    "get the current NodeMetric if it exists in the local nodes" in {
      val m1 = NodeMetrics(node1.address, newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(node2.address, newTimestamp, collector.sample.metrics)
      var gossip = MetricsGossip(window)
      gossip = gossip :+ m1
      gossip = gossip :+ m2
      gossip.previous(m1.address).nonEmpty must be(true)
    }

    "get the address keys for nodes for a given collection" in {
      val m1 = NodeMetrics(node1.address, newTimestamp, collector.sample.metrics)
      val m2 = NodeMetrics(node2.address, newTimestamp, collector.sample.metrics)
      val m3 = NodeMetrics(node3.address, newTimestamp, collector.sample.metrics)
      var local = MetricsGossip(window)
      local = local :+ m1
      local = local :+ m2
      var remote = MetricsGossip(window)
      remote = remote :+ m3

      remote.nodeKeys.contains(m3.address) must be(true)
      local.nodeKeys.contains(m2.address) must be(true)
    }
  }
}

