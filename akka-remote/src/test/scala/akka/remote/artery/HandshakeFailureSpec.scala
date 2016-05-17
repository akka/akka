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

object HandshakeFailureSpec {

  val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = $portA
       remote.handshake-timeout = 2s
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
      def sel = system.actorSelection(s"akka.artery://systemB@localhost:$portB/user/echo")
      sel ! "hello"
      expectNoMsg(3.seconds) // longer than handshake-timeout

      systemB = ActorSystem("systemB", HandshakeFailureSpec.configB)
      systemB.actorOf(TestActors.echoActorProps, "echo")

      within(10.seconds) {
        awaitAssert {
          println(s"# identify $sel") // FIXME
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
