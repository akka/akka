/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.routing

// TODO remove metrics

import org.scalatest.WordSpec
import org.scalatest.Matchers

import akka.actor.Address
import akka.cluster.Metric
import akka.cluster.NodeMetrics
import akka.cluster.StandardMetrics._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsSelectorSpec extends WordSpec with Matchers {

  val abstractSelector = new CapacityMetricsSelector {
    override def capacity(nodeMetrics: Set[NodeMetrics]): Map[Address, Double] = Map.empty
  }

  val a1 = Address("akka.tcp", "sys", "a1", 2551)
  val b1 = Address("akka.tcp", "sys", "b1", 2551)
  val c1 = Address("akka.tcp", "sys", "c1", 2551)
  val d1 = Address("akka.tcp", "sys", "d1", 2551)

  val decayFactor = Some(0.18)

  val nodeMetricsA = NodeMetrics(a1, System.currentTimeMillis, Set(
    Metric.create(HeapMemoryUsed, 128, decayFactor),
    Metric.create(HeapMemoryCommitted, 256, decayFactor),
    Metric.create(HeapMemoryMax, 512, None),
    Metric.create(CpuCombined, 0.1, decayFactor),
    Metric.create(SystemLoadAverage, 0.5, None),
    Metric.create(Processors, 8, None)).flatten)

  val nodeMetricsB = NodeMetrics(b1, System.currentTimeMillis, Set(
    Metric.create(HeapMemoryUsed, 256, decayFactor),
    Metric.create(HeapMemoryCommitted, 512, decayFactor),
    Metric.create(HeapMemoryMax, 1024, None),
    Metric.create(CpuCombined, 0.5, decayFactor),
    Metric.create(SystemLoadAverage, 1.0, None),
    Metric.create(Processors, 16, None)).flatten)

  val nodeMetricsC = NodeMetrics(c1, System.currentTimeMillis, Set(
    Metric.create(HeapMemoryUsed, 1024, decayFactor),
    Metric.create(HeapMemoryCommitted, 1024, decayFactor),
    Metric.create(HeapMemoryMax, 1024, None),
    Metric.create(CpuCombined, 1.0, decayFactor),
    Metric.create(SystemLoadAverage, 16.0, None),
    Metric.create(Processors, 16, None)).flatten)

  val nodeMetricsD = NodeMetrics(d1, System.currentTimeMillis, Set(
    Metric.create(HeapMemoryUsed, 511, decayFactor),
    Metric.create(HeapMemoryCommitted, 512, decayFactor),
    Metric.create(HeapMemoryMax, 512, None),
    Metric.create(Processors, 2, decayFactor)).flatten)

  val nodeMetrics = Set(nodeMetricsA, nodeMetricsB, nodeMetricsC, nodeMetricsD)

  "CapacityMetricsSelector" must {

    "calculate weights from capacity" in {
      val capacity = Map(a1 -> 0.6, b1 -> 0.3, c1 -> 0.1)
      val weights = abstractSelector.weights(capacity)
      weights should ===(Map(c1 -> 1, b1 -> 3, a1 -> 6))
    }

    "handle low and zero capacity" in {
      val capacity = Map(a1 -> 0.0, b1 -> 1.0, c1 -> 0.005, d1 -> 0.004)
      val weights = abstractSelector.weights(capacity)
      weights should ===(Map(a1 -> 0, b1 -> 100, c1 -> 1, d1 -> 0))
    }

  }

  "HeapMetricsSelector" must {
    "calculate capacity of heap metrics" in {
      val capacity = HeapMetricsSelector.capacity(nodeMetrics)
      capacity(a1) should ===(0.75 +- 0.0001)
      capacity(b1) should ===(0.75 +- 0.0001)
      capacity(c1) should ===(0.0 +- 0.0001)
      capacity(d1) should ===(0.001953125 +- 0.0001)
    }
  }

  "CpuMetricsSelector" must {
    "calculate capacity of cpuCombined metrics" in {
      val capacity = CpuMetricsSelector.capacity(nodeMetrics)
      capacity(a1) should ===(0.9 +- 0.0001)
      capacity(b1) should ===(0.5 +- 0.0001)
      capacity(c1) should ===(0.0 +- 0.0001)
      capacity.contains(d1) should ===(false)
    }
  }

  "SystemLoadAverageMetricsSelector" must {
    "calculate capacity of systemLoadAverage metrics" in {
      val capacity = SystemLoadAverageMetricsSelector.capacity(nodeMetrics)
      capacity(a1) should ===(0.9375 +- 0.0001)
      capacity(b1) should ===(0.9375 +- 0.0001)
      capacity(c1) should ===(0.0 +- 0.0001)
      capacity.contains(d1) should ===(false)
    }
  }

  "MixMetricsSelector" must {
    "aggregate capacity of all metrics" in {
      val capacity = MixMetricsSelector.capacity(nodeMetrics)
      capacity(a1) should ===((0.75 + 0.9 + 0.9375) / 3 +- 0.0001)
      capacity(b1) should ===((0.75 + 0.5 + 0.9375) / 3 +- 0.0001)
      capacity(c1) should ===((0.0 + 0.0 + 0.0) / 3 +- 0.0001)
      capacity(d1) should ===((0.001953125) / 1 +- 0.0001)
    }
  }

}

