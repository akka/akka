package akka.remote.transport

import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor._
import akka.testkit.{ TimingTest, DefaultTimeout, ImplicitSender, AkkaSpec }
import ThrottlerTransportAdapterSpec._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.remote.transport.ThrottlerTransportAdapter.{ Direction, TokenBucket, SetThrottle }
import akka.remote.RemoteActorRefProvider
import akka.testkit.TestEvent
import akka.testkit.EventFilter
import akka.remote.EndpointException

object ThrottlerTransportAdapterSpec {
  val configA: Config = ConfigFactory parseString ("""
    akka {
      #loglevel = DEBUG
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remoting.retry-latch-closed-for = 0 s
      remoting.log-remote-lifecycle-events = on

      remoting.transports.tcp.applied-adapters = ["trttl"]
      remoting.transports.tcp.port = 0
    }
                                                   """)

  class Echo extends Actor {
    override def receive = {
      case "ping" ⇒ sender ! "pong"
    }
  }

  val PingPacketSize = 148
  val MessageCount = 100
  val BytesPerSecond = 500
  val TotalTime = (MessageCount * PingPacketSize) / BytesPerSecond

  class ThrottlingTester(remote: ActorRef, controller: ActorRef) extends Actor {
    var messageCount = MessageCount
    var received = 0
    var startTime = 0L

    override def receive = {
      case "start" ⇒
        self ! "sendNext"
        startTime = System.nanoTime()
      case "sendNext" ⇒ if (messageCount > 0) {
        remote ! "ping"
        self ! "sendNext"
        messageCount -= 1
      }
      case "pong" ⇒
        received += 1
        if (received >= MessageCount) controller ! (System.nanoTime() - startTime)
    }
  }
}

class ThrottlerTransportAdapterSpec extends AkkaSpec(configA) with ImplicitSender with DefaultTimeout {

  val systemB = ActorSystem("systemB", system.settings.config)
  val remote = systemB.actorOf(Props[Echo], "echo")

  val rootB = RootActorPath(systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress)
  val here = system.actorFor(rootB / "user" / "echo")

  "ThrottlerTransportAdapter" must {
    "maintain average message rate" taggedAs TimingTest in {
      Await.result(
        system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
          .managementCommand(SetThrottle(Address("akka", "systemB", "localhost", rootB.address.port.get), Direction.Send, TokenBucket(200, 500, 0, 0))), 3.seconds)
      val tester = system.actorOf(Props(new ThrottlingTester(here, self))) ! "start"

      expectMsgPF((TotalTime + 3).seconds) {
        case time: Long ⇒ log.warning("Total time of transmission: " + NANOSECONDS.toSeconds(time))
      }
    }
  }

  override def beforeTermination() {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.warning(source = "akka://AkkaProtocolStressTest/user/$a", start = "received dead letter"),
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    systemB.eventStream.publish(TestEvent.Mute(
      EventFilter[EndpointException](),
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
  }

  override def afterTermination(): Unit = systemB.shutdown()
}

class ThrottlerTransportAdapterGenericSpec extends GenericTransportSpec(withAkkaProtocol = true) {

  def transportName = "ThrottlerTransportAdapter"
  def schemeIdentifier = "trttl.akka"
  def freshTransport(testTransport: TestTransport) =
    new ThrottlerTransportAdapter(testTransport, system.asInstanceOf[ExtendedActorSystem])

}
