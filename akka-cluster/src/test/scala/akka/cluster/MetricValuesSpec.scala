/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.util.Try
import akka.actor.Address
import akka.testkit.AkkaSpec
import akka.cluster.StandardMetrics._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricValuesSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricsCollectorFactory {

  val collector = createMetricsCollector

  val node1 = NodeMetrics(Address("akka", "sys", "a", 2554), 1, collector.sample.metrics)
  val node2 = NodeMetrics(Address("akka", "sys", "a", 2555), 1, collector.sample.metrics)

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
      stream1 must be >= (stream2)
    }

    "extract expected MetricValue types for load balancing" in {
      nodes foreach { node ⇒
        node match {
          case HeapMemory(address, _, used, committed, Some(max)) ⇒
            committed must be >= (used)
            used must be <= (max)
            committed must be <= (max)
            // extract is the java api
            StandardMetrics.extractHeapMemory(node) must not be (null)
          case HeapMemory(address, _, used, committed, None) ⇒
            used must be > (0L)
            committed must be > (0L)
            // extract is the java api
            StandardMetrics.extractCpu(node) must not be (null)
        }

        node match {
          case Cpu(address, _, systemLoadAverageOption, cpuCombinedOption, processors) ⇒
            processors must be > (0)
            if (systemLoadAverageOption.isDefined)
              systemLoadAverageOption.get must be >= (0.0)
            if (cpuCombinedOption.isDefined) {
              cpuCombinedOption.get must be <= (1.0)
              cpuCombinedOption.get must be >= (0.0)
            }
            // extract is the java api
            StandardMetrics.extractCpu(node) must not be (null)
        }
      }
    }
  }

}