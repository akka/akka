/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit.AkkaSpec
import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import akka.remote.FailureDetector.Clock

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AccrualFailureDetectorSpec extends AkkaSpec("akka.loglevel = INFO") {

  "An AccrualFailureDetector" must {

    def fakeTimeGenerator(timeIntervals: Seq[Long]): Clock = new Clock {
      @volatile var times = timeIntervals.tail.foldLeft(List[Long](timeIntervals.head))((acc, c) ⇒ acc ::: List[Long](acc.last + c))
      override def apply(): Long = {
        val currentTime = times.head
        times = times.tail
        currentTime
      }
    }

    def createFailureDetector(
      threshold: Double = 8.0,
      maxSampleSize: Int = 1000,
      minStdDeviation: FiniteDuration = 10.millis,
      acceptableLostDuration: FiniteDuration = Duration.Zero,
      firstHeartbeatEstimate: FiniteDuration = 1.second,
      clock: Clock = FailureDetector.defaultClock) =
      new PhiAccrualFailureDetector(
        threshold,
        maxSampleSize,
        minStdDeviation,
        acceptableLostDuration,
        firstHeartbeatEstimate = firstHeartbeatEstimate)(clock = clock)

    "use good enough cumulative distribution function" in {
      val fd = createFailureDetector()
      fd.cumulativeDistributionFunction(0.0, 0, 1) must be(0.5 plusOrMinus (0.001))
      fd.cumulativeDistributionFunction(0.6, 0, 1) must be(0.7257 plusOrMinus (0.001))
      fd.cumulativeDistributionFunction(1.5, 0, 1) must be(0.9332 plusOrMinus (0.001))
      fd.cumulativeDistributionFunction(2.0, 0, 1) must be(0.97725 plusOrMinus (0.01))
      fd.cumulativeDistributionFunction(2.5, 0, 1) must be(0.9379 plusOrMinus (0.1))
      fd.cumulativeDistributionFunction(3.5, 0, 1) must be(0.99977 plusOrMinus (0.1))
      fd.cumulativeDistributionFunction(4.0, 0, 1) must be(0.99997 plusOrMinus (0.1))

      for (x :: y :: Nil ← (0.0 to 4.0 by 0.1).toList.sliding(2)) {
        fd.cumulativeDistributionFunction(x, 0, 1) must be < (
          fd.cumulativeDistributionFunction(y, 0, 1))
      }

      fd.cumulativeDistributionFunction(2.2, 2.0, 0.3) must be(0.7475 plusOrMinus (0.001))
    }

    "return realistic phi values" in {
      val fd = createFailureDetector()
      val test = TreeMap(0 -> 0.0, 500 -> 0.1, 1000 -> 0.3, 1200 -> 1.6, 1400 -> 4.7, 1600 -> 10.8, 1700 -> 15.3)
      for ((timeDiff, expectedPhi) ← test) {
        fd.phi(timeDiff = timeDiff, mean = 1000.0, stdDeviation = 100.0) must be(expectedPhi plusOrMinus (0.1))
      }

      // larger stdDeviation results => lower phi
      fd.phi(timeDiff = 1100, mean = 1000.0, stdDeviation = 500.0) must be < (
        fd.phi(timeDiff = 1100, mean = 1000.0, stdDeviation = 100.0))
    }

    "return phi value of 0.0 on startup for each address, when no heartbeats" in {
      val fd = createFailureDetector()
      fd.phi must be(0.0)
      fd.phi must be(0.0)
    }

    "return phi based on guess when only one heartbeat" in {
      val timeInterval = List[Long](0, 1000, 1000, 1000, 1000)
      val fd = createFailureDetector(firstHeartbeatEstimate = 1.seconds,
        clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat()
      fd.phi must be(0.3 plusOrMinus 0.2)
      fd.phi must be(4.5 plusOrMinus 0.3)
      fd.phi must be > (15.0)
    }

    "return phi value using first interval after second heartbeat" in {
      val timeInterval = List[Long](0, 100, 100, 100)
      val fd = createFailureDetector(clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat()
      fd.phi must be > (0.0)
      fd.heartbeat()
      fd.phi must be > (0.0)
    }

    "mark node as available after a series of successful heartbeats" in {
      val timeInterval = List[Long](0, 1000, 100, 100)
      val fd = createFailureDetector(clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()

      fd.isAvailable must be(true)
    }

    "mark node as dead if heartbeat are missed" in {
      val timeInterval = List[Long](0, 1000, 100, 100, 7000)
      val fd = createFailureDetector(threshold = 3, clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat() //0
      fd.heartbeat() //1000
      fd.heartbeat() //1100

      fd.isAvailable must be(true) //1200
      fd.isAvailable must be(false) //8200
    }

    "mark node as available if it starts heartbeat again after being marked dead due to detection of failure" in {
      val timeInterval = List[Long](0, 1000, 100, 1100, 7000, 100, 1000, 100, 100)
      val fd = createFailureDetector(threshold = 3, clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat() //0
      fd.heartbeat() //1000
      fd.heartbeat() //1100
      fd.isAvailable must be(true) //1200
      fd.isAvailable must be(false) //8200
      fd.heartbeat() //8300
      fd.heartbeat() //9300
      fd.heartbeat() //9400

      fd.isAvailable must be(true) //9500
    }

    "accept some configured missing heartbeats" in {
      val timeInterval = List[Long](0, 1000, 1000, 1000, 4000, 1000, 1000)
      val fd = createFailureDetector(acceptableLostDuration = 3.seconds, clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.isAvailable must be(true)
      fd.heartbeat()
      fd.isAvailable must be(true)
    }

    "fail after configured acceptable missing heartbeats" in {
      val timeInterval = List[Long](0, 1000, 1000, 1000, 1000, 1000, 500, 500, 5000)
      val fd = createFailureDetector(acceptableLostDuration = 3.seconds, clock = fakeTimeGenerator(timeInterval))

      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.heartbeat()
      fd.isAvailable must be(true)
      fd.heartbeat()
      fd.isAvailable must be(false)
    }

    "use maxSampleSize heartbeats" in {
      val timeInterval = List[Long](0, 100, 100, 100, 100, 600, 1000, 1000, 1000, 1000, 1000)
      val fd = createFailureDetector(maxSampleSize = 3, clock = fakeTimeGenerator(timeInterval))

      // 100 ms interval
      fd.heartbeat() //0
      fd.heartbeat() //100
      fd.heartbeat() //200
      fd.heartbeat() //300
      val phi1 = fd.phi //400
      // 1000 ms interval, should become same phi when 100 ms intervals have been dropped
      fd.heartbeat() //1000
      fd.heartbeat() //2000
      fd.heartbeat() //3000
      fd.heartbeat() //4000
      val phi2 = fd.phi //5000
      phi2 must be(phi1.plusOrMinus(0.001))
    }

  }

  "Statistics for heartbeats" must {

    "calculate correct mean and variance" in {
      val samples = Seq(100, 200, 125, 340, 130)
      val stats = (HeartbeatHistory(maxSampleSize = 20) /: samples) {
        (stats, value) ⇒ stats :+ value
      }
      stats.mean must be(179.0 plusOrMinus 0.00001)
      stats.variance must be(7584.0 plusOrMinus 0.00001)
    }

    "have 0.0 variance for one sample" in {
      (HeartbeatHistory(600) :+ 1000L).variance must be(0.0 plusOrMinus 0.00001)
    }

    "be capped by the specified maxSampleSize" in {
      val history3 = HeartbeatHistory(maxSampleSize = 3) :+ 100 :+ 110 :+ 90
      history3.mean must be(100.0 plusOrMinus 0.00001)
      history3.variance must be(66.6666667 plusOrMinus 0.00001)

      val history4 = history3 :+ 140
      history4.mean must be(113.333333 plusOrMinus 0.00001)
      history4.variance must be(422.222222 plusOrMinus 0.00001)

      val history5 = history4 :+ 80
      history5.mean must be(103.333333 plusOrMinus 0.00001)
      history5.variance must be(688.88888889 plusOrMinus 0.00001)

    }
  }
}
