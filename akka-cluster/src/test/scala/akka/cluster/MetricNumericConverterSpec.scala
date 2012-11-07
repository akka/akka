/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.cluster.NodeMetrics.MetricValues._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricNumericConverterSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricNumericConverter with ImplicitSender with MetricSpec
  with MetricsCollectorFactory {

  "MetricNumericConverter" must {
    val collector = createMetricsCollector

    "convert " in {
      convertNumber(0).isLeft must be(true)
      convertNumber(1).left.get must be(1)
      convertNumber(1L).isLeft must be(true)
      convertNumber(0.0).isRight must be(true)
    }

    "define a new metric" in {
      val metric = Metric(HeapMemoryUsed, Some(256L), decay = Some(10))
      metric.name must be(HeapMemoryUsed)
      metric.isDefined must be(true)
      metric.value must be(Some(256L))
      metric.average.isDefined must be(true)
      metric.average.get.ewma must be(256L)

      collector match {
        case c: SigarMetricsCollector ⇒
          val cores = c.totalCores
          cores.isDefined must be(true)
          cores.value.get.intValue must be > (0)
        case _ ⇒
      }
    }

    "define an undefined value with a None " in {
      Metric("x", Some(-1), None).value.isDefined must be(false)
      Metric("x", Some(java.lang.Double.NaN), None).value.isDefined must be(false)
      Metric("x", None, None).isDefined must be(false)
    }

    "recognize whether a metric value is defined" in {
      defined(0) must be(true)
      defined(0.0) must be(true)
    }

    "recognize whether a metric value is not defined" in {
      defined(-1) must be(false)
      defined(Double.NaN) must be(false)
    }
  }
}