package akka.remote

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import java.net.InetSocketAddress

class AccrualFailureDetectorSpec extends WordSpec with MustMatchers {

  "An AccrualFailureDetector" should {

    "mark node as available after a series of successful heartbeats" in {
      val fd = new AccrualFailureDetector
      val conn = new InetSocketAddress("localhost", 2552)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }

    "mark node as dead after explicit removal of connection" in {
      val fd = new AccrualFailureDetector
      val conn = new InetSocketAddress("localhost", 2552)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)

      fd.remove(conn)

      fd.isAvailable(conn) must be(false)
    }

    "mark node as dead if heartbeat are missed" in {
      val fd = new AccrualFailureDetector(threshold = 3)
      val conn = new InetSocketAddress("localhost", 2552)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)

      Thread.sleep(5000)

      fd.isAvailable(conn) must be(false)
    }

    "mark node as available if it starts heartbeat again after being marked dead due to detection of failure" in {
      val fd = new AccrualFailureDetector(threshold = 3)
      val conn = new InetSocketAddress("localhost", 2552)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)

      Thread.sleep(5000)
      fd.isAvailable(conn) must be(false)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }
  }
}