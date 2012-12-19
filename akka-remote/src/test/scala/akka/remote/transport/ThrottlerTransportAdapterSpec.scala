package akka.remote.transport

import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor._
import akka.testkit.{ TimingTest, DefaultTimeout, ImplicitSender, AkkaSpec }
import ThrottlerTransportAdapterSpec._
import scala.concurrent.duration._
import akka.remote.transport.TestTransport.{ WriteAttempt, AssociationRegistry }
import scala.concurrent.{ Promise, Future, Await }
import akka.remote.transport.Transport.{ Ready, InboundAssociation, Status }
import akka.util.ByteString
import akka.remote.transport.AssociationHandle.InboundPayload
import akka.remote.transport.ThrottlerTransportAdapter.{ Direction, TokenBucket, SetThrottle }
import akka.remote.RemoteActorRefProvider

object ThrottlerTransportAdapterSpec {
  val configA: Config = ConfigFactory parseString ("""
    akka {
      #loglevel = DEBUG
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remoting.retry-latch-closed-for = 0 s
      remoting.log-remote-lifecycle-events = on

      remoting.transports.tcp.applied-adapters = ["trttl"]
      remoting.transports.tcp.port = 12345
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
  val configB = ConfigFactory.parseString("akka.remoting.transports.tcp.port = 12346")
    .withFallback(system.settings.config).resolve()

  val systemB = ActorSystem("systemB", configB)
  val remote = systemB.actorOf(Props[Echo], "echo")

  val here = system.actorFor("tcp.trttl.akka://systemB@localhost:12346/user/echo")

  "ThrottlerTransportAdapter" must {
    "maintain average message rate" taggedAs TimingTest in {
      Await.result(
        system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
          .managementCommand(SetThrottle(Address("akka", "systemB", "localhost", 12346), Direction.Send, TokenBucket(200, 500, 0, 0))), 3 seconds)
      val tester = system.actorOf(Props(new ThrottlingTester(here, self))) ! "start"

      expectMsgPF((TotalTime + 3) seconds) {
        case time: Long ⇒ log.warning("Total time of transmission: " + NANOSECONDS.toSeconds(time))
      }
    }
  }

  override def atTermination(): Unit = systemB.shutdown()
}

class ThrottlerTransportAdapterGenericSpec extends GenericTransportSpec(withAkkaProtocol = true) {

  def transportName = "ThrottlerTransportAdapter"
  def schemeIdentifier = "trttl.akka"
  def freshTransport(testTransport: TestTransport) =
    new ThrottlerTransportAdapter(testTransport, system.asInstanceOf[ExtendedActorSystem])

}
