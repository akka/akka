/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.actor.Address
import akka.testkit.{ LongRunningTest, AkkaSpec }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AccrualFailureDetectorSpec extends AkkaSpec("""
  actor.provider = "akka.remote.RemoteActorRefProvider"
  akka.loglevel = "INFO"
""") {

  "An AccrualFailureDetector" must {
    val conn = Address("akka", "", "localhost", 2552)
    val conn2 = Address("akka", "", "localhost", 2553)

    def fakeTimeGenerator(timeIntervals: Seq[Long]): () ⇒ Long = {
      var times = timeIntervals.tail.foldLeft(List[Long](timeIntervals.head))((acc, c) ⇒ acc ::: List[Long](acc.last + c))
      def timeGenerator(): Long = {
        val currentTime = times.head
        times = times.tail
        currentTime
      }
      timeGenerator
    }

    "return phi value of 0.0 on startup for each address, when no heartbeats" in {
      val fd = new AccrualFailureDetector(system)
      fd.phi(conn) must be(0.0)
      fd.phi(conn2) must be(0.0)
    }

    "return phi based on guess when only one heartbeat" in {
      // 1 second ticks
      val timeInterval = Vector.fill(30)(1000L)
      val fd = new AccrualFailureDetector(system,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn)
      fd.phi(conn) must be > (0.0)
      // let time go
      for (n ← 2 to 8)
        fd.phi(conn) must be < (4.0)
      for (n ← 9 to 18)
        fd.phi(conn) must be < (8.0)

      fd.phi(conn) must be > (8.0)
    }

    "return phi value using first interval after second heartbeat" in {
      val timeInterval = List[Long](0, 100, 100, 100)
      val fd = new AccrualFailureDetector(system,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn)
      fd.phi(conn) must be > (0.0)
      fd.heartbeat(conn)
      fd.phi(conn) must be > (0.0)
    }

    "mark node as available after a series of successful heartbeats" in {
      val timeInterval = List[Long](0, 1000, 100, 100)
      val fd = new AccrualFailureDetector(system,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn)
      fd.heartbeat(conn)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }

    "mark node as dead after explicit removal of connection" in {
      val timeInterval = List[Long](0, 1000, 100, 100, 100)
      val fd = new AccrualFailureDetector(system,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn)
      fd.heartbeat(conn)
      fd.heartbeat(conn)
      fd.isAvailable(conn) must be(true)
      fd.remove(conn)

      fd.isAvailable(conn) must be(false)
    }

    "mark node as available after explicit removal of connection and receiving heartbeat again" in {
      val timeInterval = List[Long](0, 1000, 100, 1100, 1100, 1100, 1100, 1100, 100)
      val fd = new AccrualFailureDetector(system,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn) //0

      fd.heartbeat(conn) //1000
      fd.heartbeat(conn) //1100

      fd.isAvailable(conn) must be(true) //2200

      fd.remove(conn)

      fd.isAvailable(conn) must be(false) //3300

      // it receives heartbeat from an explicitly removed node
      fd.heartbeat(conn) //4400
      fd.heartbeat(conn) //5500
      fd.heartbeat(conn) //6600

      fd.isAvailable(conn) must be(true) //6700
    }

    "mark node as dead if heartbeat are missed" in {
      val timeInterval = List[Long](0, 1000, 100, 100, 5000)
      val ft = fakeTimeGenerator(timeInterval)
      val fd = new AccrualFailureDetector(system, threshold = 3,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn) //0
      fd.heartbeat(conn) //1000
      fd.heartbeat(conn) //1100

      fd.isAvailable(conn) must be(true) //1200
      fd.isAvailable(conn) must be(false) //6200
    }

    "mark node as available if it starts heartbeat again after being marked dead due to detection of failure" in {
      val timeInterval = List[Long](0, 1000, 100, 1100, 5000, 100, 1000, 100, 100)
      val fd = new AccrualFailureDetector(system, threshold = 3,
        timeMachine = fakeTimeGenerator(timeInterval))

      fd.heartbeat(conn) //0
      fd.heartbeat(conn) //1000
      fd.heartbeat(conn) //1100
      fd.isAvailable(conn) must be(true) //1200
      fd.isAvailable(conn) must be(false) //6200
      fd.heartbeat(conn) //6300
      fd.heartbeat(conn) //7300
      fd.heartbeat(conn) //7400

      fd.isAvailable(conn) must be(true) //7500
    }

    "use maxSampleSize heartbeats" in {
      val timeInterval = List[Long](0, 100, 100, 100, 100, 600, 1000, 1000, 1000, 1000, 1000)
      val fd = new AccrualFailureDetector(system, maxSampleSize = 3,
        timeMachine = fakeTimeGenerator(timeInterval))

      // 100 ms interval
      fd.heartbeat(conn) //0
      fd.heartbeat(conn) //100
      fd.heartbeat(conn) //200
      fd.heartbeat(conn) //300
      val phi1 = fd.phi(conn) //400
      // 1000 ms interval, should become same phi when 100 ms intervals have been dropped
      fd.heartbeat(conn) //1000
      fd.heartbeat(conn) //2000
      fd.heartbeat(conn) //3000
      fd.heartbeat(conn) //4000
      val phi2 = fd.phi(conn) //5000
      phi2 must be(phi1.plusOrMinus(0.001))
    }

  }
}
