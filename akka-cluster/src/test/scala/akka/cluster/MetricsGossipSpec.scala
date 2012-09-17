/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.concurrent.util.duration._
import scala.concurrent.util.Duration

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.Address

import java.lang.System.{ currentTimeMillis ⇒ newTimestamp }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsGossipSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractClusterMetricsSpec with MetricSpec {

  val collector = createMetricsCollector
  var localGossip = MetricsGossip(window)
  var remoteGossip = MetricsGossip(window)

  val m1 = NodeMetrics(Address("akka", "sys", "a", 2554), newTimestamp, collector.sample.metrics)
  val m2 = NodeMetrics(Address("akka", "sys", "a", 2555), newTimestamp, collector.sample.metrics)
  val m3 = NodeMetrics(Address("akka", "sys", "a", 2556), newTimestamp, collector.sample.metrics)
  val m2Updated = m2 copy (metrics = collector.sample.metrics, timestamp = newTimestamp)

  "A MetricsGossip" must {
    // retain the order of tests for brevity

    "add and initialize new NodeMetrics" in {
      localGossip :+= m1
      localGossip.nodes.size must be(1)
      localGossip.nodeKeys.size must be(localGossip.nodes.size)
      assertMasterMetricsAgainstGossipMetrics(Set(m1), localGossip)
      assertExpectedSampleSize(collector.isSigar, localGossip)
      assertInitialized(localGossip.rateOfDecay, collectNodeMetrics(localGossip.nodes).toSet)

      localGossip :+= m2
      localGossip.nodes.size must be(2)
      localGossip.nodeKeys.size must be(localGossip.nodes.size)
      assertMasterMetricsAgainstGossipMetrics(Set(m1, m2), localGossip)
      assertExpectedSampleSize(collector.isSigar, localGossip)
      assertInitialized(localGossip.rateOfDecay, collectNodeMetrics(localGossip.nodes).toSet)
    }

    "merge peer metrics" in {
      remoteGossip :+= m3
      remoteGossip :+= m2
      remoteGossip.nodes.size must be(2)
      val beforeMergeNodes = remoteGossip.nodes

      remoteGossip :+= m2Updated // merge peers
      remoteGossip.nodes.size must be(2)
      assertMasterMetricsAgainstGossipMetrics(beforeMergeNodes, remoteGossip)
      assertExpectedSampleSize(collector.isSigar, remoteGossip)
      val peer = remoteGossip.nodes find (_.address == m2Updated.address)
      peer.get.timestamp must be > m2.timestamp
    }

    "merge a existing metric set for a node and update node ring" in {
      localGossip.nodes.size must be(2)
      remoteGossip.nodes.size must be(2)
      // must contain nodes 1,3, and the most recent version of m2
      val mergedGossip = localGossip merge remoteGossip
      mergedGossip.nodes.size must be(3)
      assertExpectedNodeAddresses(mergedGossip, Set(m1, m2, m3))
      assertExpectedSampleSize(collector.isSigar, mergedGossip)
      assertCreatedUninitialized(mergedGossip)
      assertInitialized(mergedGossip)
      mergedGossip.nodes.find(_.address == m2.address).get.timestamp must be(m2Updated.timestamp)
    }

    "get the current NodeMetrics if it exists in the local nodes" in {
      localGossip.metricsFor(m1).nonEmpty must be(true)
    }

    "get the address keys for nodes for a given collection" in {
      remoteGossip.nodeKeys.contains(m3.address) must be(true)
      localGossip.nodeKeys.contains(m1.address) must be(true)
    }

    "remove a node if it is no longer Up" in {
      localGossip.nodes.size must be(2)
      localGossip = localGossip remove m1.address
      localGossip.nodes.size must be(1)
      localGossip.nodes.exists(_.address == m1.address) must be(false)
    }
  }
}

