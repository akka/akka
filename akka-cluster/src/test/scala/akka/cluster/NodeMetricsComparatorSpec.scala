/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NodeMetricsComparatorSpec extends ClusterMetricsRouteeSelectorSpec {

  import NodeMetrics._

  "NodeMetrics.MetricValues" must {
    "extract expected metrics for load balancing" in {
      val stream1 = node2.metric("heap-memory-committed").value.get.longValue()
      val stream2 = node1.metric("heap-memory-used").value.get.longValue()
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
          systemLoadAverage must be >= (0.0)
          processors must be > (0)
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

  "NodeMetricsComparator" must {
    val seq = Seq((Address("akka", "sys", "a", 2554), 9L), (Address("akka", "sys", "a", 2555), 8L))
    val seq2 = Seq((Address("akka", "sys", "a", 2554), 9.0), (Address("akka", "sys", "a", 2555), 8.0))

    "handle min ordering of a (Address, Long)" in {
      import NodeMetricsComparator.longMinAddressOrdering
      seq.min._1.port.get must be(2555)
      seq.min._2 must be(8L)
    }
    "handle max ordering of a (Address, Long)" in {
      val (address, value) = NodeMetricsComparator.maxAddressLong(seq)
      value must be(9L)
      address.port.get must be(2554)
    }
    "handle min ordering of a (Address, Double)" in {
      import NodeMetricsComparator.doubleMinAddressOrdering
      seq2.min._1.port.get must be(2555)
      seq2.min._2 must be(8.0)
    }
    "handle max ordering of a (Address, Double)" in {
      val (address, value) = NodeMetricsComparator.maxAddressDouble(seq2)
      value must be(9.0)
      address.port.get must be(2554)
    }
  }
}