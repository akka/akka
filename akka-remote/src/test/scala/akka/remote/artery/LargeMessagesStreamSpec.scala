/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorRef, ActorSelection, ActorSystem, ExtendedActorSystem, Props, RootActorPath }
import akka.remote.{ LargeDestination, RegularDestination, RemoteActorRef }
import akka.testkit.{ SocketUtil, TestKit, TestProbe }
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ ShouldMatchers, WordSpec }

import scala.concurrent.Await
import scala.concurrent.duration._

object LargeMessagesStreamSpec {
  case class Ping(payload: ByteString = ByteString.empty)
  case class Pong(bytesReceived: Long)
  class EchoSize extends Actor {
    def receive = {
      case Ping(bytes) ⇒ sender() ! Pong(bytes.size)
    }
  }
}

class LargeMessagesStreamSpec extends WordSpec with ShouldMatchers with ScalaFutures {
  import LargeMessagesStreamSpec._

  val config = ConfigFactory.parseString(
    s"""
      akka {
        loglevel = ERROR
        actor {
          provider = "akka.remote.RemoteActorRefProvider"
        }
        remote.artery {
          enabled = on
          hostname = localhost
          port = 0
          large-message-destinations = [
            "/user/large"
          ]
        }
      }

    """)

  "The large message support" should {

    "not affect regular communication" in {
      val systemA = ActorSystem("systemA", config)
      val systemB = ActorSystem("systemB", config)

      try {
        val senderProbeA = TestProbe()(systemA)
        val senderProbeB = TestProbe()(systemB)

        // start actor and make sure it is up and running
        val regular = systemB.actorOf(Props(new EchoSize), "regular")
        regular.tell(Ping(), senderProbeB.ref)
        senderProbeB.expectMsg(Pong(0))

        // communicate with it from the other system
        val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val rootB = RootActorPath(addressB)
        val regularRemote = awaitResolve(systemA.actorSelection(rootB / "user" / "regular"))
        regularRemote.tell(Ping(), senderProbeA.ref)
        senderProbeA.expectMsg(Pong(0))

        // flag should be cached now
        regularRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(RegularDestination)

      } finally {
        TestKit.shutdownActorSystem(systemA)
        TestKit.shutdownActorSystem(systemB)
      }
    }

    "pass small regular messages over the large-message stream" in {
      val systemA = ActorSystem("systemA", config)
      val systemB = ActorSystem("systemB", config)

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
        TestKit.shutdownActorSystem(systemA)
        TestKit.shutdownActorSystem(systemB)
      }
    }

    "allow for normal communication while simultaneously sending large messages" in {
      val systemA = ActorSystem("systemA", config)
      val systemB = ActorSystem("systemB", config)

      try {

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
        val remoteProbe = TestProbe()(systemA)

        val largeBytes = 2000000
        largeRemote.tell(Ping(ByteString.fromArray(Array.ofDim[Byte](largeBytes))), remoteProbe.ref)
        regularRemote.tell(Ping(), remoteProbe.ref)

        // should be no problems sending regular small messages while large messages are being sent
        remoteProbe.expectMsg(Pong(0))
        remoteProbe.expectMsg(10.seconds, Pong(largeBytes))

        // cached flags should be set now
        largeRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(LargeDestination)
        regularRemote.asInstanceOf[RemoteActorRef].cachedLargeMessageDestinationFlag should ===(RegularDestination)

      } finally {
        TestKit.shutdownActorSystem(systemA)
        TestKit.shutdownActorSystem(systemB)
      }
    }
  }

  def awaitResolve(selection: ActorSelection): ActorRef = Await.result(selection.resolveOne(3.seconds), 3.seconds)
}
