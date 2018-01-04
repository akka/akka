package akka.remote.artery

import akka.actor.ActorSystem
import akka.remote.RemoteTransportException
import akka.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }

class ArteryFailedToBindSpec extends WordSpec with Matchers {

  "an ActorSystem" should {
    "not start if port is taken" in {
      val port = SocketUtil.temporaryLocalPort(true)
      val config = ConfigFactory.parseString(
        s"""
           |akka {
           |  actor {
           |    provider = remote
           |  }
           |  remote {
           |    artery {
           |      enabled = on
           |      canonical.hostname = "127.0.0.1"
           |      canonical.port = $port
           |      log-aeron-counters = on
           |    }
           |  }
           |}
       """.stripMargin)
      val as = ActorSystem("BindTest1", config)
      try {
        val ex = intercept[RemoteTransportException] {
          ActorSystem("BindTest2", config)
        }
        ex.getMessage should equal("Inbound Aeron channel is in errored state. See Aeron logs for details.")
      } finally {
        as.terminate()
      }
    }
  }
}
