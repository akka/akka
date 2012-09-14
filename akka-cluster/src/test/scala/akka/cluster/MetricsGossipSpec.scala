/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.actor.Address
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import System.{ currentTimeMillis ⇒ newTimestamp }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsGossipSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractClusterMetricsSpec {

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
      localGossip = localGossip :+ m1
      localGossip.nodes.size must be(1)
      assert(m1.metrics.filter(_.isDefined).toSeq)

      localGossip = localGossip :+ m2
      localGossip.nodes.size must be(2)
      localGossip.nodeKeys.size must be(2)
      assert(m1.metrics.filter(_.isDefined).toSeq ++ m2.metrics.filter(_.isDefined).toSeq)
    }

    "merge peer metrics for a node by most recent Metric timestamps" in {
      remoteGossip = remoteGossip :+ m3
      remoteGossip = remoteGossip :+ m2
      val beforeMergeMetrics = collectNodeMetrics(remoteGossip)
      remoteGossip.nodes.size must be(2)
      remoteGossip = remoteGossip :+ m2Updated // merge peers
      remoteGossip.nodes.size must be(2)
      val afterMergeMetrics = collectNodeMetrics(remoteGossip)
      afterMergeMetrics.size must be(beforeMergeMetrics.size)
      val peer = remoteGossip.nodes find (_.address == m2Updated.address)
      peer.get.timestamp must be > m2.timestamp
    }

    "merge a existing metric set for a node and update node ring" in {
      // must contain nodes 1,3, and the most recent version of m2
      val mergedGossip = localGossip merge remoteGossip
      mergedGossip.nodes.size must be(3)
      mergedGossip.nodes exists (_.address == m1.address) must be(true)
      mergedGossip.nodes exists (_.address == m2.address) must be(true)
      mergedGossip.nodes exists (_.address == m3.address) must be(true)
      mergedGossip.nodes.find(_.address == m2.address).get.timestamp must be(m2Updated.timestamp)
      mergedGossip.nodes foreach (n ⇒ n.metrics foreach (m ⇒ if (m.trendable) m.average.isDefined must be(true)))
    }

    "get the current NodeMetrics if it exists in the local nodes" in {
      localGossip.metricsFor(m1).nonEmpty must be(true)
    }

    "get the address keys for nodes for a given collection" in {
      remoteGossip.nodeKeys.contains(m3.address) must be(true)
      localGossip.nodeKeys.contains(m1.address) must be(true)
    }

    "remove a node if it has become unreachable, down, etc." in {
      localGossip = localGossip - m1.address
      localGossip.nodes.size must be(1)
    }

    def assert(master: Seq[Metric]): Unit = {
      var latest = collectNodeMetrics(localGossip)
      latest.size must be(master.size)
      latest.toSet filter (_.trendable) foreach { m ⇒
        m.value.isDefined must be(true)
        m.average.isDefined must be(true)
      }
    }

    def collectNodeMetrics(gossip: MetricsGossip): Seq[Metric] = {
      var latest: Seq[Metric] = Seq.empty
      gossip.nodes.foreach(n ⇒ latest ++= n.metrics)
      latest
    }
  }
}

