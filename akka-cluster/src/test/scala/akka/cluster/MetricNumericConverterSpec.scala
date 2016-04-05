/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

// TODO remove metrics

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.StandardMetrics._
import scala.util.Failure

class MetricNumericConverterSpec extends WordSpec with Matchers with MetricNumericConverter {

  "MetricNumericConverter" must {

    "convert" in {
      convertNumber(0).isLeft should ===(true)
      convertNumber(1).left.get should ===(1)
      convertNumber(1L).isLeft should ===(true)
      convertNumber(0.0).isRight should ===(true)
    }

    "define a new metric" in {
      val Some(metric) = Metric.create(HeapMemoryUsed, 256L, decayFactor = Some(0.18))
      metric.name should ===(HeapMemoryUsed)
      metric.value should ===(256L)
      metric.isSmooth should ===(true)
      metric.smoothValue should ===(256.0 +- 0.0001)
    }

    "define an undefined value with a None " in {
      Metric.create("x", -1, None).isDefined should ===(false)
      Metric.create("x", java.lang.Double.NaN, None).isDefined should ===(false)
      Metric.create("x", Failure(new RuntimeException), None).isDefined should ===(false)
    }

    "recognize whether a metric value is defined" in {
      defined(0) should ===(true)
      defined(0.0) should ===(true)
    }

    "recognize whether a metric value is not defined" in {
      defined(-1) should ===(false)
      defined(-1.0) should ===(false)
      defined(Double.NaN) should ===(false)
    }
  }
}
