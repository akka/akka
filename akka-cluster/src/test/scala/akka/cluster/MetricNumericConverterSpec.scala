/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.cluster.StandardMetrics.HeapMemory.Fields._
import scala.util.Try

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricNumericConverterSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricNumericConverter with ImplicitSender
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
      val Some(metric) = Metric.create(HeapMemoryUsed, 256L, decayFactor = Some(0.18))
      metric.name must be(HeapMemoryUsed)
      metric.value must be(256L)
      metric.isSmooth must be(true)
      metric.smoothValue must be(256.0 plusOrMinus 0.0001)
    }

    "define an undefined value with a None " in {
      Metric.create("x", -1, None).isDefined must be(false)
      Metric.create("x", java.lang.Double.NaN, None).isDefined must be(false)
      Metric.create("x", Try(throw new RuntimeException), None).isDefined must be(false)
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