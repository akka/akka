package akka.remote

import java.net.InetSocketAddress
import akka.testkit.AkkaSpec
import akka.actor.Address

class AccrualFailureDetectorSpec extends AkkaSpec("""
  akka.loglevel = "DEBUG"
""") {

  "An AccrualFailureDetector" must {
    val conn = Address("akka", "", Some("localhost"), Some(2552))

    "mark node as available after a series of successful heartbeats" in {
      val fd = new AccrualFailureDetector(system = system)

      fd.heartbeat(conn)

      Thread.sleep(1000)
      fd.heartbeat(conn)

      Thread.sleep(100)
      fd.heartbeat(conn)

      fd.isAvailable(conn) must be(true)
    }

    // FIXME how should we deal with explicit removal of connection? - if triggered as failure then we have a problem in boostrap - see line 142 in AccrualFailureDetector
    "mark node as dead after explicit removal of connection" ignore {
      val fd = new AccrualFailureDetector(system = system)

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
      val fd = new AccrualFailureDetector(threshold = 3, system = system)

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
      val fd = new AccrualFailureDetector(threshold = 3, system = system)

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
