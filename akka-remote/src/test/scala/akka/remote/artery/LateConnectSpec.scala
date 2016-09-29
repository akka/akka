/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.actor.RootActorPath
import akka.remote.RARP
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.SocketUtil
import akka.testkit.TestActors
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory

object LateConnectSpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
       remote.artery.advanced.handshake-timeout = 3s
       remote.artery.advanced.image-liveness-timeout = 2.9s
     }
  """)

}

class LateConnectSpec extends AkkaSpec(LateConnectSpec.config) with ImplicitSender {

  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort
  val configB = ConfigFactory.parseString(s"akka.remote.artery.canonical.port = $portB")
    .withFallback(system.settings.config)
  lazy val systemB = ActorSystem("systemB", configB)

  "Connection" must {

    "be established after initial lazy restart" in {
      system.actorOf(TestActors.echoActorProps, "echoA")

      val echoB = system.actorSelection(s"akka://systemB@localhost:$portB/user/echoB")
      echoB ! "ping1"

      // let the outbound streams be restarted (lazy), systemB is not started yet
      Thread.sleep((RARP(system).provider.remoteSettings.Artery.Advanced.HandshakeTimeout + 1.second).toMillis)

      // start systemB
      systemB.actorOf(TestActors.echoActorProps, "echoB")

      val probeB = TestProbe()(systemB)
      val echoA = systemB.actorSelection(RootActorPath(RARP(system).provider.getDefaultAddress) / "user" / "echoA")
      echoA.tell("ping2", probeB.ref)
      probeB.expectMsg(10.seconds, "ping2")

      echoB ! "ping3"
      expectMsg("ping3")
    }
  }

  override def afterTermination(): Unit = shutdown(systemB)

}
