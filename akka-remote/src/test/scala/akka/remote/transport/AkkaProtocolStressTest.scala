package akka.remote.transport

import akka.testkit.{ TimingTest, DefaultTimeout, ImplicitSender, AkkaSpec }
import com.typesafe.config.{ Config, ConfigFactory }
import AkkaProtocolStressTest._
import akka.actor._
import scala.concurrent.duration._
import akka.testkit.TestEvent
import akka.testkit.EventFilter
import akka.remote.EndpointException

object AkkaProtocolStressTest {
  val configA: Config = ConfigFactory parseString ("""
    akka {
      #loglevel = DEBUG
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remoting.retry-latch-closed-for = 0 s
      remoting.log-remote-lifecycle-events = on

      remoting.failure-detector {
        threshold = 1.0
        max-sample-size = 2
        min-std-deviation = 1 ms
        acceptable-heartbeat-pause = 0.01 s
      }
      remoting.retry-window = 1 s
      remoting.maximum-retries-in-window = 1000

      remoting.transports.tcp {
        applied-adapters = ["gremlin"]
        port = 0
      }

    }
                                                   """)

  class SequenceVerifier(remote: ActorRef, controller: ActorRef) extends Actor {
    val limit = 10000
    var nextSeq = 0
    var maxSeq = -1
    var losses = 0

    def receive = {
      case "start" ⇒ self ! "sendNext"
      case "sendNext" ⇒ if (nextSeq < limit) {
        remote ! nextSeq
        nextSeq += 1
        self ! "sendNext"
      }
      case seq: Int ⇒
        if (seq > maxSeq) {
          losses += seq - maxSeq - 1
          maxSeq = seq
          if (seq > limit * 0.9) {
            controller ! (maxSeq, losses)
          }
        } else {
          controller ! "Received out of order message. Previous: ${maxSeq} Received: ${seq}"
        }
    }
  }

}

class AkkaProtocolStressTest extends AkkaSpec(configA) with ImplicitSender with DefaultTimeout {

  val systemB = ActorSystem("systemB", system.settings.config)
  val remote = systemB.actorOf(Props(new Actor {
    def receive = {
      case seq: Int ⇒ sender ! seq
    }
  }), "echo")

  val rootB = RootActorPath(systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress)
  val here = system.actorFor(rootB / "user" / "echo")

  "AkkaProtocolTransport" must {
    "guarantee at-most-once delivery and message ordering despite packet loss" taggedAs TimingTest in {
      val tester = system.actorOf(Props(new SequenceVerifier(here, self))) ! "start"

      expectMsgPF(30 seconds) {
        case (received: Int, lost: Int) ⇒
          log.debug(s" ######## Received ${received - lost} messages from ${received} ########")
      }
      system.eventStream.publish(TestEvent.Mute(
        EventFilter.warning(source = "akka://AkkaProtocolStressTest/user/$a", start = "received dead letter"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
      systemB.eventStream.publish(TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    }
  }

  override def atTermination(): Unit = systemB.shutdown()

}
