/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import scala.concurrent.duration._
import akka.testkit.{ LongRunningTest, AkkaSpec }
import scala.concurrent.forkjoin.ThreadLocalRandom
import System.currentTimeMillis

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DataStreamSpec extends AkkaSpec(MetricsEnabledSpec.config) with MetricSpec with MetricsCollectorFactory {
  import system.dispatcher

  val collector = createMetricsCollector

  "DataStream" must {

    "calcualate same ewma for constant values" in {
      val ds = DataStream(decay = 5, ewma = 100.0, currentTimeMillis, currentTimeMillis) :+
        100.0 :+ 100.0 :+ 100.0
      ds.ewma must be(100.0 plusOrMinus 0.001)
    }

    "calcualate correct ewma for normal decay" in {
      val d0 = DataStream(decay = 10, ewma = 1000.0, currentTimeMillis, currentTimeMillis)
      d0.ewma must be(1000.0 plusOrMinus 0.01)
      val d1 = d0 :+ 10.0
      d1.ewma must be(820.0 plusOrMinus 0.01)
      val d2 = d1 :+ 10.0
      d2.ewma must be(672.73 plusOrMinus 0.01)
      val d3 = d2 :+ 10.0
      d3.ewma must be(552.23 plusOrMinus 0.01)
      val d4 = d3 :+ 10.0
      d4.ewma must be(453.64 plusOrMinus 0.01)

      val dn = (1 to 100).foldLeft(d0)((d, _) ⇒ d :+ 10.0)
      dn.ewma must be(10.0 plusOrMinus 0.1)
    }

    "calculate ewma for decay 1" in {
      val d0 = DataStream(decay = 1, ewma = 100.0, currentTimeMillis, currentTimeMillis)
      d0.ewma must be(100.0 plusOrMinus 0.01)
      val d1 = d0 :+ 1.0
      d1.ewma must be(1.0 plusOrMinus 0.01)
      val d2 = d1 :+ 57.0
      d2.ewma must be(57.0 plusOrMinus 0.01)
      val d3 = d2 :+ 10.0
      d3.ewma must be(10.0 plusOrMinus 0.01)
    }

    "calculate the ewma for multiple, variable, data streams" taggedAs LongRunningTest in {
      var streamingDataSet = Map.empty[String, Metric]
      var usedMemory = Array.empty[Byte]
      (1 to 50) foreach { _ ⇒
        Thread.sleep(100)
        usedMemory = usedMemory ++ Array.fill(1024)(ThreadLocalRandom.current.nextInt(127).toByte)
        val changes = collector.sample.metrics.flatMap { latest ⇒
          streamingDataSet.get(latest.name) match {
            case None ⇒ Some(latest)
            case Some(previous) ⇒
              if (latest.average.isDefined && latest.isDefined && latest.value.get != previous.value.get) {
                val updated = previous :+ latest
                updated.average.isDefined must be(true)
                val updatedAverage = updated.average.get
                updatedAverage.timestamp must be > (previous.average.get.timestamp)
                updatedAverage.duration.length must be > (previous.average.get.duration.length)
                updatedAverage.ewma must not be (previous.average.get.ewma)
                (previous.value.get.longValue compare latest.value.get.longValue) must be(
                  previous.average.get.ewma.longValue compare updatedAverage.ewma.longValue)
                Some(updated)
              } else None
          }
        }
        streamingDataSet ++= changes.map(m ⇒ m.name -> m).toMap
      }
    }
  }
}
