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

object HandshakeFailureSpec {

  // need the port before systemB is started
  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = 0
       remote.artery.advanced.handshake-timeout = 2s
     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.port = $portB")
    .withFallback(commonConfig)

}

class HandshakeFailureSpec extends AkkaSpec(HandshakeFailureSpec.commonConfig) with ImplicitSender {
  import HandshakeFailureSpec._

  var systemB: ActorSystem = null

  "Artery handshake" must {

    "allow for timeout and later connect" in {
      def sel = system.actorSelection(s"artery://systemB@localhost:$portB/user/echo")
      sel ! "hello"
      expectNoMsg(3.seconds) // longer than handshake-timeout

      systemB = ActorSystem("systemB", HandshakeFailureSpec.configB)
      systemB.actorOf(TestActors.echoActorProps, "echo")

      within(10.seconds) {
        awaitAssert {
          sel ! "hello2"
          expectMsg(1.second, "hello2")
        }
      }

      sel ! Identify(None)
      val remoteRef = expectMsgType[ActorIdentity].ref.get

      remoteRef ! "ping"
      expectMsg("ping")
    }

  }

  override def afterTermination(): Unit =
    if (systemB != null) shutdown(systemB)

}
