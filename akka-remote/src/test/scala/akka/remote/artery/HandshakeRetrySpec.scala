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

object HandshakeRetrySpec {

  val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = $portA
       remote.handshake-timeout = 10s
     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.port = $portB")
    .withFallback(commonConfig)

}

class HandshakeRetrySpec extends AkkaSpec(HandshakeRetrySpec.commonConfig) with ImplicitSender {
  import HandshakeRetrySpec._

  var systemB: ActorSystem = null

  "Artery handshake" must {

    "be retried during handshake-timeout (no message loss)" in {
      def sel = system.actorSelection(s"akka.artery://systemB@localhost:$portB/user/echo")
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
