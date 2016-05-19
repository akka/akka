/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorRef, ActorSelection, ActorSystem, ExtendedActorSystem, Props, RootActorPath }
import akka.remote.{ LargeDestination, RegularDestination, RemoteActorRef }
import akka.testkit.{ SocketUtil, TestProbe }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ ShouldMatchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

object LargeMessagesChannelSpec {
  case class Ping(payload: ByteString = ByteString.empty)
  case class Pong(bytesReceived: Long)
  class EchoSize extends Actor {
    def receive = {
      case Ping(bytes) ⇒ sender() ! Pong(bytes.size)
    }
  }
}

class LargeMessagesChannelSpec extends WordSpec with ShouldMatchers with ScalaFutures {
  import LargeMessagesChannelSpec._

  def config(port: Int) = ConfigFactory.parseString(
    s"""
      akka {
        loglevel = ERROR
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote.artery {
          enabled = on
          hostname = localhost
          port = $port
          large-message-destinations = [
            "/user/large"
          ]
        }
      }

    """)

  "The large message support" should {

    "not affect regular communication" in {
      val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)
      val systemA = ActorSystem("systemA", config(portA))
      val systemB = ActorSystem("systemB", config(portB))

      try {
        val senderProbeA = TestProbe()(systemA)
        val senderProbeB = TestProbe()(systemB)

        // start actor and make sure it is up and running
        val large = systemB.actorOf(Props(new EchoSize), "regular")
        large.tell(Ping(), senderProbeB.ref)
        senderProbeB.expectMsg(Pong(0))

        // communicate with it from the other system
        val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val rootB = RootActorPath(addressB)
        val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "regular"))
        largeRemote.tell(Ping(), senderProbeA.ref)
        senderProbeA.expectMsg(Pong(0))

        // flag should be cached now
        largeRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(RegularDestination)

      } finally {
        systemA.terminate()
        systemB.terminate()
      }
    }

    "pass small regular messages over the large-message channel" in {
      val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)
      val systemA = ActorSystem("systemA", config(portA))
      val systemB = ActorSystem("systemB", config(portB))

      try {
        val senderProbeA = TestProbe()(systemA)
        val senderProbeB = TestProbe()(systemB)

        // start actor and make sure it is up and running
        val large = systemB.actorOf(Props(new EchoSize), "large")
        large.tell(Ping(), senderProbeB.ref)
        senderProbeB.expectMsg(Pong(0))

        // communicate with it from the other system
        val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val rootB = RootActorPath(addressB)
        val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "large"))
        largeRemote.tell(Ping(), senderProbeA.ref)
        senderProbeA.expectMsg(Pong(0))

        // flag should be cached now
        largeRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(LargeDestination)

      } finally {
        systemA.terminate()
        systemB.terminate()
      }
    }

    "allow for normal communication while simultaneously sending large messages" in {
      val Seq(portA, portB) = SocketUtil.temporaryServerAddresses(2, "localhost", udp = true).map(_.getPort)
      val systemA = ActorSystem("systemA", config(portA))
      val systemB = ActorSystem("systemB", config(portB))

      try {
        val senderProbeA = TestProbe()(systemA)
        val senderProbeB = TestProbe()(systemB)

        // setup two actors, one with the large flag and one regular
        val large = systemB.actorOf(Props(new EchoSize), "large")
        large.tell(Ping(), senderProbeB.ref)
        senderProbeB.expectMsg(Pong(0))

        val regular = systemB.actorOf(Props(new EchoSize), "regular")
        regular.tell(Ping(), senderProbeB.ref)
        senderProbeB.expectMsg(Pong(0))

        // both up and running, resolve remote refs
        val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val rootB = RootActorPath(addressB)
        val largeRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "large"))
        val regularRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "regular"))

        // send a large message, as well as regular one
        val largeBytes = 500000
        largeRemote.tell(Ping(ByteString.fromArray(Array.ofDim[Byte](largeBytes))), senderProbeA.ref)
        regularRemote.tell(Ping(), senderProbeA.ref)

        // this is racy but I don'd know how to test it any other way
        // should be no problems sending regular small messages
        senderProbeA.expectMsg(Pong(0))
        senderProbeA.expectMsg(10.seconds, Pong(largeBytes))

        // cached flags should be set now
        largeRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(LargeDestination)
        regularRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(RegularDestination)

      } finally {
        systemA.terminate()
        systemB.terminate()
      }
    }
  }

  def awaitResolve(selection: ActorSelection): ActorRef = Await.result(selection.resolveOne(3.seconds), 3.seconds)
}
