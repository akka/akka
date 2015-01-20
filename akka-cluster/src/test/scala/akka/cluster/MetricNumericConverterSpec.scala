/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

// TODO remove metrics

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.StandardMetrics._
import scala.util.Failure

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricNumericConverterSpec extends WordSpec with Matchers with MetricNumericConverter {

  "MetricNumericConverter" must {

    "convert" in {
      convertNumber(0).isLeft should be(true)
      convertNumber(1).left.get should be(1)
      convertNumber(1L).isLeft should be(true)
      convertNumber(0.0).isRight should be(true)
    }

    "define a new metric" in {
      val Some(metric) = Metric.create(HeapMemoryUsed, 256L, decayFactor = Some(0.18))
      metric.name should be(HeapMemoryUsed)
      metric.value should be(256L)
      metric.isSmooth should be(true)
      metric.smoothValue should be(256.0 +- 0.0001)
    }

    "define an undefined value with a None " in {
      Metric.create("x", -1, None).isDefined should be(false)
      Metric.create("x", java.lang.Double.NaN, None).isDefined should be(false)
      Metric.create("x", Failure(new RuntimeException), None).isDefined should be(false)
    }

    "recognize whether a metric value is defined" in {
      defined(0) should be(true)
      defined(0.0) should be(true)
    }

    "recognize whether a metric value is not defined" in {
      defined(-1) should be(false)
      defined(-1.0) should be(false)
      defined(Double.NaN) should be(false)
    }
  }
}
