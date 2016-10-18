/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor._
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.testkit.SocketUtil
import akka.testkit.TestActors
import com.typesafe.config.ConfigFactory

object HandshakeRetrySpec {

  // need the port before systemB is started
  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.canonical.hostname = localhost
       remote.artery.canonical.port = 0
       remote.artery.advanced.handshake-timeout = 10s
       remote.artery.advanced.image-liveness-timeout = 7s
     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.canonical.port = $portB")
    .withFallback(commonConfig)

}

class HandshakeRetrySpec extends AkkaSpec(HandshakeRetrySpec.commonConfig) with ImplicitSender {
  import HandshakeRetrySpec._

  var systemB: ActorSystem = null

  "Artery handshake" must {

    "be retried during handshake-timeout (no message loss)" in {
      def sel = system.actorSelection(s"akka://systemB@localhost:$portB/user/echo")
      sel ! "hello"
      expectNoMsg(1.second)

      systemB = ActorSystem("systemB", HandshakeRetrySpec.configB)
      systemB.actorOf(TestActors.echoActorProps, "echo")

      expectMsg("hello")

      sel ! Identify(None)
      val remoteRef = expectMsgType[ActorIdentity].ref.get

      remoteRef ! "ping"
      expectMsg("ping")
    }

  }

  override def afterTermination(): Unit =
    if (systemB != null) shutdown(systemB)

}
