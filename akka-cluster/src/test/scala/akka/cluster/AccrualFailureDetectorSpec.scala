package akka.cluster

import java.net.InetSocketAddress
import akka.testkit.AkkaSpec
import akka.actor.Address

class AccrualFailureDetectorSpec extends AkkaSpec("""
  akka.loglevel = "INFO"
""") {

  "An AccrualFailureDetector" must {
    val conn = Address("akka", "", "localhost", 2552)
    val conn2 = Address("akka", "", "localhost", 2553)

    "return phi value of 0.0D on startup for each address" in {
      val fd = new AccrualFailureDetector(system, conn)
      fd.phi(conn) must be(0.0D)
      fd.phi(conn2) must be(0.0D)
    }

    "mark node as available after a series of successful heartbeats" in {
      val fd = new AccrualFailureDetector(system, conn)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }

    // FIXME how should we deal with explicit removal of connection? - if triggered as failure then we have a problem in boostrap - see line 142 in AccrualFailureDetector
    "mark node as dead after explicit removal of connection" ignore {
      val fd = new AccrualFailureDetector(system, conn)

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
      val fd = new AccrualFailureDetector(system, conn, threshold = 3)

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
      val fd = new AccrualFailureDetector(system, conn, threshold = 3)

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
