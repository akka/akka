/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NodeMetricsSpec extends AkkaSpec with AbstractClusterMetricsSpec {

  val collector = createMetricsCollector
  val sample1 = collector.sample
  val sample2 = collector.sample
  sample1.copy(metrics = sample1.metrics.filter(_.isDefined))
  sample2.copy(metrics = sample2.metrics.filter(_.isDefined))
  val sample3 = NodeMetrics(Address("akka", "sys", "a", 2554), 0, sample1.metrics)

  "NodeMetrics must" must {
    "recognize 2 updatable nodes" in { (sample1 updatable sample2) must be(true) }
    "recognize 2 not updatable nodes" in {
      (sample2 updatable sample1) must be(false)
      (sample2 updatable sample3) must be(false)
    }
    "return correct result for 2 'same' nodes" in { (sample1 same sample2) must be(true) }
    "return correct result for 2 not 'same' nodes" in { (sample1 same sample3) must be(false) }

    "merge 2 NodeMetrics by most recent" in {
      val master = sample2
      val merged1 = sample1 merge master
      merged1.timestamp must be(master.timestamp)
      val sla = master.metrics.find(_.name == "system-load-average").get
      merged1.metrics.filter(_.same(sla)).size must be(1)
      merged1.metrics must be(master.metrics)
    }

    "not merge 2 NodeMetrics by most recent" in {
      val master = sample2
      val merged1 = master merge sample3 // older and not same
      merged1.timestamp must be(master.timestamp)
      merged1.metrics must not be (sample3.metrics)
    }
  }
}

