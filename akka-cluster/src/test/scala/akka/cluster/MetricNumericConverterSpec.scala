/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.cluster.StandardMetrics._
import scala.util.Failure

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricNumericConverterSpec extends WordSpec with MustMatchers with MetricNumericConverter {

  "MetricNumericConverter" must {

    "convert" in {
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
      Metric.create("x", Failure(new RuntimeException), None).isDefined must be(false)
    }

    "recognize whether a metric value is defined" in {
      defined(0) must be(true)
      defined(0.0) must be(true)
    }

    "recognize whether a metric value is not defined" in {
      defined(-1) must be(false)
      defined(-1.0) must be(false)
      defined(Double.NaN) must be(false)
    }
  }
}