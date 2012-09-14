/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricSpec extends AkkaSpec with AbstractClusterMetricsSpec {

  val collector = createMetricsCollector

  "A Metric must" must {

    "Create and initialize a new metric or merge an existing one" in {
      for (i ← 0 to samples) {
        val metrics = collector.sample.metrics
        metrics foreach { m ⇒
          m.name must not be (null)
          m.average.isEmpty must be(true)
          if (m.value.isDefined) m.defined(m.value.get) must be(true)
          else m.initializable must be(false)
          if (m.initializable) (m.trendable && m.isDefined && m.average.isEmpty) must be(true)
        }

        val initialized = metrics map (_.initialize(window))
        initialized.size must be(metrics.size)
        initialized foreach { m ⇒
          m.initializable must be(false)
          if (m.trendable) m.average.isDefined must be(true)
        }
      }
    }

    "merge 2 metrics that are tracking the same metric" in {
      for (i ← 0 to samples) {
        val sample1 = collector.sample.metrics map (_.initialize(window))
        val sample2 = collector.sample.metrics map (_.initialize(window))
        val merged = sample2 flatMap (metric ⇒ sample1 collect {
          case peer if metric same peer ⇒ metric :+ peer
        })
        merged.size must be(sample1.size)
        merged.size must be(sample2.size)
      }
    }
  }
}
