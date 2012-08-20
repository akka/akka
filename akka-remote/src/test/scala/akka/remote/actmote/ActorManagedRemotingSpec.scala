package akka.remote.actmote

import akka.testkit._
import akka.actor._
import akka.remote._
import scala.concurrent.util.duration._
import com.typesafe.config.ConfigFactory
import scala.concurrent.Await
import akka.pattern.ask

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorManagedRemotingSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote {
    transport = "akka.remote.actmote.ActorManagedRemoting"
    log-received-messages = on
    log-sent-messages = on
  }
  remote.netty {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /blub.remote = "akka://remote-sys@localhost:12346"
    /looker/child.remote = "akka://remote-sys@localhost:12346"
    /looker/child/grandchild.remote = "akka://RemoteCommunicationSpec@localhost:12345"
  }
}
""") with ImplicitSender with DefaultTimeout {

  val conf = ConfigFactory.parseString("akka.remote.netty.port=12346").withFallback(system.settings.config)
  val remoteSystem = ActorSystem("remote-sys", conf)
  val remoteActor = remoteSystem.actorOf(Props(new Actor {
    def receive = {
      case "ping"    ⇒ sender ! "pong"
      case "discard" ⇒
      case i: Int    ⇒
    }
  }), "echo")

  val remoteReference = system.actorFor("akka://remote-sys@localhost:12346/user/echo")
  val remoteTransport = remoteSystem.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
  val remoteAddress = remoteTransport.address

  val transportUnderTest = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.asInstanceOf[ActorManagedRemoting]
  val transportHeadActor = transportUnderTest.headActor
  val transportProvider = transportUnderTest.transport.asInstanceOf[DummyTransportConnector]

  val outboundLink = transportUnderTest.address -> remoteAddress
  val inboundLink = remoteAddress -> transportUnderTest.address
  def connectionPresent(link: (Address, Address)): Boolean = DummyTransportMedium.isConnected(link)
  def outboundConnectionPresent: Boolean = connectionPresent(outboundLink)
  def inboundConnectionPresent: Boolean = connectionPresent(inboundLink)

  def activityLog = DummyTransportMedium.activityLog.reverse

  def withCleanTransport(testCode: ⇒ Any) {
    transportUnderTest.start
    remoteTransport.start
    try {
      testCode
    } finally {
      transportUnderTest.shutdown
      remoteTransport.shutdown
      DummyTransportMedium.clear
    }
  }

  import DummyTransportMedium.SendAttempt
  import DummyTransportMedium.ConnectionAttempt

  "Actor based remoting" must {

    "return the local address" in withCleanTransport {
      assert(transportUnderTest.address.toString === "akka://ActorManagedRemotingSpec@localhost:12345")
    }

    "connect to remote address at the first send" in withCleanTransport {
      remoteReference ! "discard"
      awaitCond(outboundConnectionPresent)
    }

    "be able to cleanly close open connections" in withCleanTransport {
      remoteReference ! "discard"
      awaitCond(outboundConnectionPresent)
      transportUnderTest.shutdownClientConnection(remoteAddress)
      awaitCond(!outboundConnectionPresent)
    }

    "shut down the underlying transport provider properly when shutting down" in {
      transportUnderTest.start
      val underlyingTransport = transportUnderTest.transport
      transportUnderTest.shutdown
      assert(underlyingTransport.isTerminated)
    }

    /*"create endpoint actors for incoming and outgoing connections" in {
      fail
    }*/

    "able to send messages after successful connection" in withCleanTransport {
      remoteReference ! "discard"
      remoteReference ! "discard"
      val localAddress = transportUnderTest.address
      awaitCond(
        activityLog.count {
          case SendAttempt("discard", localAddress, remoteAddress) ⇒ true
          case _ ⇒ false
        } == 2)
    }

    "keep the order of sent messages" in withCleanTransport {
      val messageCount = 10
      val messages = (1 to messageCount).toList
      val expected = messages map { DummyTransportMedium.SendAttempt(_, transportUnderTest.address, remoteAddress) }

      messages foreach { msg ⇒ remoteReference ! msg }

      def messageLog = activityLog.filter {
        case SendAttempt(_, localAddress, remoteAddress) ⇒ true
        case _ ⇒ false
      }

      awaitCond(messageLog.size == messageCount)
      assert(messageLog === expected)
    }

    "reuse outbound connection if there is one already opened" in withCleanTransport {
      remoteReference ! "discard"
      awaitCond(outboundConnectionPresent)
      remoteReference ! "discard"
      awaitCond(activityLog.count {
        case SendAttempt(_, _, _) ⇒ true
        case _                    ⇒ false
      } == 2)

      val connectAttempts = activityLog.count {
        case ConnectionAttempt(_) ⇒ true
        case _                    ⇒ false
      }

      assert(connectAttempts === 1)
    }

    "retry connecting if it is failed first" in withCleanTransport {
      DummyTransportMedium.reject(transportUnderTest.address)
      remoteReference ! "discard"

      def connectAttempts = activityLog.count {
        case ConnectionAttempt(_) ⇒ true
        case _                    ⇒ false
      }

      awaitCond(connectAttempts > 1)

      DummyTransportMedium.allow(transportUnderTest.address)
      awaitCond(outboundConnectionPresent)

      activityLog.exists {
        case SendAttempt("discard", localAddress, remoteAddress) ⇒ true
        case _ ⇒ false
      }
    }

    "retry connecting if it timed out first" in withCleanTransport {
      DummyTransportMedium.silentDrop(transportUnderTest.address)
      remoteReference ! "discard"

      def connectAttempts = activityLog.count {
        case ConnectionAttempt(_) ⇒ true
        case _                    ⇒ false
      }

      awaitCond(connectAttempts > 1, 5 seconds)

      DummyTransportMedium.allow(transportUnderTest.address)
      awaitCond(outboundConnectionPresent)

      activityLog.exists {
        case SendAttempt("discard", localAddress, remoteAddress) ⇒ true
        case _ ⇒ false
      }
    }

    // TEST watches

    /*"accept inbound connections and create endpoint actors" in withCleanTransport {
      fail
    }*/

    "reuse exising inbound connections if use-passive-connections is set" in withCleanTransport {
      // Do a bidirectional communication step first
      remoteReference ! "ping"
      expectMsgPF() {
        case "pong" ⇒ true
      }
      assert(outboundConnectionPresent)
      assert(!inboundConnectionPresent)
    }

    //TODO: test for different actor systems living on the same host and port but with different names

    /*"not reuse exising inbound connections if use-passive-connections is cleared" in withCleanTransport {
      // Do a bidirectional communication step first
      remoteReference ! "ping"
      expectMsgPF() {
        case "pong" ⇒ true
      }
      assert(outboundConnectionPresent)
      assert(inboundConnectionPresent)
    }

    "throttle connection attempts after reaching a configurable limit" in withCleanTransport {
      fail
    }*/

    "restart endpoint if an unexpected error (bug) happens without affecting other endpoints" in withCleanTransport {
      DummyTransportMedium.crash(transportUnderTest.address)
      remoteReference ! "discard"

      def connectAttempts = activityLog.count {
        case ConnectionAttempt(_) ⇒ true
        case _                    ⇒ false
      }

      awaitCond(connectAttempts > 0, 5 seconds)
      DummyTransportMedium.allow(transportUnderTest.address)
      awaitCond(outboundConnectionPresent)
    }

    /*"reconnect endpoints if connection problems occur" in withCleanTransport {
      fail
    }

    "reset connection if a malformed message arrives" in withCleanTransport {
      fail
    }
    
    "drop messages after the number undeliverable messages reach a configurable limit" in withCleanTransport {
    }

    "appropriately dispatch incoming messages" in withCleanTransport {
      fail
    }

    "reject and log if a misrouted message arrives" in withCleanTransport {
      fail
    }

    "reject and log if an unauthenticated message arrives" in withCleanTransport {
      fail
    }
    */
  }

}