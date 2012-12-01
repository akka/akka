/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import scala.concurrent.duration._
import akka.testkit.{ LongRunningTest, AkkaSpec }
import scala.concurrent.forkjoin.ThreadLocalRandom

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class EWMASpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricsCollectorFactory {
  import system.dispatcher

  val collector = createMetricsCollector

  "DataStream" must {

    "calcualate same ewma for constant values" in {
      val ds = EWMA(value = 100.0, alpha = 0.18) :+
        100.0 :+ 100.0 :+ 100.0
      ds.value must be(100.0 plusOrMinus 0.001)
    }

    "calcualate correct ewma for normal decay" in {
      val d0 = EWMA(value = 1000.0, alpha = 2.0 / (1 + 10))
      d0.value must be(1000.0 plusOrMinus 0.01)
      val d1 = d0 :+ 10.0
      d1.value must be(820.0 plusOrMinus 0.01)
      val d2 = d1 :+ 10.0
      d2.value must be(672.73 plusOrMinus 0.01)
      val d3 = d2 :+ 10.0
      d3.value must be(552.23 plusOrMinus 0.01)
      val d4 = d3 :+ 10.0
      d4.value must be(453.64 plusOrMinus 0.01)

      val dn = (1 to 100).foldLeft(d0)((d, _) ⇒ d :+ 10.0)
      dn.value must be(10.0 plusOrMinus 0.1)
    }

    "calculate ewma for alpha 1.0, max bias towards latest value" in {
      val d0 = EWMA(value = 100.0, alpha = 1.0)
      d0.value must be(100.0 plusOrMinus 0.01)
      val d1 = d0 :+ 1.0
      d1.value must be(1.0 plusOrMinus 0.01)
      val d2 = d1 :+ 57.0
      d2.value must be(57.0 plusOrMinus 0.01)
      val d3 = d2 :+ 10.0
      d3.value must be(10.0 plusOrMinus 0.01)
    }

    "calculate alpha from half-life and collect interval" in {
      // according to http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average
      val expectedAlpha = 0.1
      // alpha = 2.0 / (1 + N)
      val n = 19
      val halfLife = n.toDouble / 2.8854
      val collectInterval = 1.second
      val halfLifeDuration = (halfLife * 1000).millis
      EWMA.alpha(halfLifeDuration, collectInterval) must be(expectedAlpha plusOrMinus 0.001)
    }

    "calculate sane alpha from short half-life" in {
      val alpha = EWMA.alpha(1.millis, 3.seconds)
      alpha must be <= (1.0)
      alpha must be >= (0.0)
      alpha must be(1.0 plusOrMinus 0.001)
    }

    "calculate sane alpha from long half-life" in {
      val alpha = EWMA.alpha(1.day, 3.seconds)
      alpha must be <= (1.0)
      alpha must be >= (0.0)
      alpha must be(0.0 plusOrMinus 0.001)
    }

    "calculate the ewma for multiple, variable, data streams" taggedAs LongRunningTest in {
      var streamingDataSet = Map.empty[String, Metric]
      var usedMemory = Array.empty[Byte]
      (1 to 50) foreach { _ ⇒
        // wait a while between each message to give the metrics a chance to change
        Thread.sleep(100)
        usedMemory = usedMemory ++ Array.fill(1024)(ThreadLocalRandom.current.nextInt(127).toByte)
        val changes = collector.sample.metrics.flatMap { latest ⇒
          streamingDataSet.get(latest.name) match {
            case None ⇒ Some(latest)
            case Some(previous) ⇒
              if (latest.isSmooth && latest.value != previous.value) {
                val updated = previous :+ latest
                updated.isSmooth must be(true)
                updated.smoothValue must not be (previous.smoothValue)
                Some(updated)
              } else None
          }
        }
        streamingDataSet ++= changes.map(m ⇒ m.name -> m)
      }
    }
  }
}
