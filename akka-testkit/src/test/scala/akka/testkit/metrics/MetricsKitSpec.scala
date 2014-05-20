/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfter, MustMatchers, WordSpec }
import com.typesafe.config.ConfigFactory

class MetricsKitSpec extends WordSpec with MustMatchers with BeforeAndAfter with BeforeAndAfterAll
  with MetricsKit {

  override def metricsConfig = ConfigFactory.load()

  val KitKey = MetricKey.fromString("metrics-kit")

  after {
    clearMetrics()
  }

  override def afterAll() {
    shutdownMetrics()
  }

  "MetricsKit" must {

    "allow measuring file descriptor usage" in {
      measureFileDescriptors(KitKey / "file-desc")

      registeredMetrics.count(_._1 contains "file-descriptor") must be > 0
    }

    "allow to measure time, on known number of operations" in {
      timedWithKnownOps(KitKey, ops = 10) {
        2 + 2
      }
    }

    "allow to measure average value using Gauge, given multiple values" in {
      val sizes = List(1L, 2L, 3L)

      val avg = averageGauge(KitKey / "avg-size")

      avg.add(sizes)
      avg.add(4)

      avg.getValue must equal(2.5)
    }
  }

}
