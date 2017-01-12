/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

// TODO remove metrics

import akka.actor.Address
import akka.testkit.AkkaSpec
import akka.cluster.StandardMetrics._

class MetricValuesSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricsCollectorFactory {

  val collector = createMetricsCollector

  val node1 = NodeMetrics(Address("akka.tcp", "sys", "a", 2554), 1, collector.sample.metrics)
  val node2 = NodeMetrics(Address("akka.tcp", "sys", "a", 2555), 1, collector.sample.metrics)

  val nodes: Seq[NodeMetrics] = {
    (1 to 100).foldLeft(List(node1, node2)) { (nodes, _) ⇒
      nodes map { n ⇒
        n.copy(metrics = collector.sample.metrics.flatMap(latest ⇒ n.metrics.collect {
          case streaming if latest sameAs streaming ⇒ streaming :+ latest
        }))
      }
    }
  }

  "NodeMetrics.MetricValues" must {
    "extract expected metrics for load balancing" in {
      val stream1 = node2.metric(HeapMemoryCommitted).get.value.longValue
      val stream2 = node1.metric(HeapMemoryUsed).get.value.longValue
      stream1 should be >= (stream2)
    }

    "extract expected MetricValue types for load balancing" in {
      nodes foreach { node ⇒
        node match {
          case HeapMemory(address, _, used, committed, _) ⇒
            used should be > (0L)
            committed should be >= (used)
            // Documentation java.lang.management.MemoryUsage says that committed <= max,
            // but in practice that is not always true (we have seen it happen). Therefore
            // we don't check the heap max value in this test.
            // extract is the java api
            StandardMetrics.extractHeapMemory(node) should not be (null)
        }

        node match {
          case Cpu(address, _, systemLoadAverageOption, cpuCombinedOption, processors) ⇒
            processors should be > (0)
            if (systemLoadAverageOption.isDefined)
              systemLoadAverageOption.get should be >= (0.0)
            if (cpuCombinedOption.isDefined) {
              cpuCombinedOption.get should be <= (1.0)
              cpuCombinedOption.get should be >= (0.0)
            }
            // extract is the java api
            StandardMetrics.extractCpu(node) should not be (null)
        }
      }
    }
  }

}
