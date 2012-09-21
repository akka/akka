/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ ImplicitSender, AkkaSpec }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricNumericConverterSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricNumericConverter with ImplicitSender with AbstractClusterMetricsSpec {

  "MetricNumericConverter" must {
    val collector = createMetricsCollector

    "convert " in {
      convert(0).isLeft must be(true)
      convert(1).left.get must be(1)
      convert(1L).isLeft must be(true)
      convert(0.0).isRight must be(true)
    }

    "define a new metric" in {
      val metric = Metric("heap-memory-used", Some(0L))
      metric.initializable must be(true)
      metric.name must not be (null)
      metric.average.isEmpty must be(true)
      metric.trendable must be(true)

      if (collector.isSigar) {
        val cores = collector.totalCores
        cores.isDefined must be(true)
        cores.value.get.intValue must be > (0)
        cores.initializable must be(false)
      }
    }

    "define an undefined value with a None " in {
      println(Metric("x", Some(-1L))) //.value.isDefined must be(false)
      println(Metric("x", Some(java.lang.Double.NaN))) //.value.isDefined must be(false)
      Metric("x", None).isDefined must be(false)
    }

    "recognize whether a metric value is defined" in {
      defined(0) must be(true)
      defined(1L) must be(true)
      defined(-1L) must be(true)
      defined(0.0) must be(true)
    }

    "recognize whether a metric value is not defined" in {
      defined(-1) must be(false)
      defined(Double.NaN) must be(false)
    }
  }
}