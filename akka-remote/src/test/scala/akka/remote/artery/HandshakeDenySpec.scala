/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.{ ActorIdentity, ActorSystem, Identify }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.testkit.SocketUtil
import akka.testkit.TestActors
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import akka.testkit.EventFilter

object HandshakeDenySpec {

  // need the port before systemB is started
  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort

  val commonConfig = ConfigFactory.parseString(s"""
     akka.loggers = ["akka.testkit.TestEventListener"]
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
       remote.artery.advanced.handshake-timeout = 2s
     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.canonical.port = $portB")
    .withFallback(commonConfig)

}

class HandshakeDenySpec extends AkkaSpec(HandshakeDenySpec.commonConfig) with ImplicitSender {
  import HandshakeDenySpec._

  var systemB: ActorSystem = null

  "Artery handshake" must {

    "be denied when originating address is unknown" in {
      def sel = system.actorSelection(s"akka://systemB@127.0.0.1:$portB/user/echo")

      systemB = ActorSystem("systemB", HandshakeDenySpec.configB)
      systemB.actorOf(TestActors.echoActorProps, "echo")

      EventFilter.debug(start = "Dropping Handshake Request addressed to unknown local address", occurrences = 1).intercept {
        sel ! Identify(None)
        expectNoMsg(3.seconds)
      }
    }

  }

  override def afterTermination(): Unit =
    if (systemB != null) shutdown(systemB)

}
