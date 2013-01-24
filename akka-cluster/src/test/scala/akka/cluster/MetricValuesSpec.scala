/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.util.Try
import akka.actor.Address
import akka.cluster.NodeMetrics.MetricValues._
import akka.testkit.AkkaSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricValuesSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricSpec
  with MetricsCollectorFactory {
  import NodeMetrics._

  val collector = createMetricsCollector

  val node1 = NodeMetrics(Address("akka", "sys", "a", 2554), 1, collector.sample.metrics)
  val node2 = NodeMetrics(Address("akka", "sys", "a", 2555), 1, collector.sample.metrics)

  var nodes: Seq[NodeMetrics] = Seq(node1, node2)

  // work up the data streams where applicable
  for (i ← 1 to 100) {
    nodes = nodes map {
      n ⇒
        n.copy(metrics = collector.sample.metrics.flatMap(latest ⇒ n.metrics.collect {
          case streaming if latest same streaming ⇒
            streaming.average match {
              case Some(e) ⇒ streaming.copy(value = latest.value, average =
                if (latest.isDefined) Some(e :+ latest.value.get.doubleValue) else None)
              case None ⇒ streaming.copy(value = latest.value)
            }
        }))
    }
  }

  "NodeMetrics.MetricValues" must {
    "extract expected metrics for load balancing" in {
      val stream1 = node2.metric(HeapMemoryCommitted).value.get.longValue
      val stream2 = node1.metric(HeapMemoryUsed).value.get.longValue
      stream1 must be >= (stream2)
    }

    "extract expected MetricValue types for load balancing" in {
      nodes foreach {
        node ⇒
          val (used, committed, max) = MetricValues.unapply(node.heapMemory)
          committed must be >= (used)
          max match {
            case Some(m) ⇒
              used must be <= (m)
              committed must be <= (m)
            case None ⇒
              used must be > (0L)
              committed must be > (0L)
          }

          val network = MetricValues.unapply(node.networkLatency)
          if (network.isDefined) {
            network.get._1 must be > (0L)
            network.get._2 must be > (0L)
          }

          val (systemLoadAverage, processors, combinedCpu, cores) = MetricValues.unapply(node.cpu)
          processors must be > (0)
          if (systemLoadAverage.isDefined)
            systemLoadAverage.get must be >= (0.0)
          if (combinedCpu.isDefined) {
            combinedCpu.get must be <= (1.0)
            combinedCpu.get must be >= (0.0)
          }
          if (cores.isDefined) {
            cores.get must be > (0)
            cores.get must be >= (processors)
          }
      }
    }
  }

}