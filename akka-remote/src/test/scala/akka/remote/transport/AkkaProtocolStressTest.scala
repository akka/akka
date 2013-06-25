package akka.remote.transport

import akka.testkit.{ TimingTest, DefaultTimeout, ImplicitSender, AkkaSpec }
import com.typesafe.config.{ Config, ConfigFactory }
import AkkaProtocolStressTest._
import akka.actor._
import scala.concurrent.duration._
import akka.testkit._
import akka.remote.{ RARP, EndpointException }
import akka.remote.transport.FailureInjectorTransportAdapter.{ One, All, Drop }
import scala.concurrent.Await

object AkkaProtocolStressTest {
  val configA: Config = ConfigFactory parseString ("""
    akka {
      #loglevel = DEBUG
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote.log-remote-lifecycle-events = on

      remote.transport-failure-detector {
        threshold = 1.0
        max-sample-size = 2
        min-std-deviation = 1 ms
        acceptable-heartbeat-pause = 0.01 s
      }
      remote.retry-window = 1 s
      # This test drops messages, but dropping too much will make it fail. The reason is that this test
      # expects at least a few of the final messages to arrive to prove that the Remoting does not get stuck
      # in an irrecoverable state. The retry limit enabled case is covered by the SystemMessageDelivery tests.
      remote.maximum-retries-in-window = 100

      remote.netty.tcp {
        applied-adapters = ["gremlin"]
        port = 0
      }

    }
                                                   """)

  class SequenceVerifier(remote: ActorRef, controller: ActorRef) extends Actor {
    import context.dispatcher

    val limit = 100000
    var nextSeq = 0
    var maxSeq = -1
    var losses = 0

    def receive = {
      case "start" ⇒ self ! "sendNext"
      case "sendNext" ⇒ if (nextSeq < limit) {
        remote ! nextSeq
        nextSeq += 1
        if (nextSeq % 2000 == 0) context.system.scheduler.scheduleOnce(500.milliseconds, self, "sendNext")
        else self ! "sendNext"
      }
      case seq: Int ⇒
        if (seq > maxSeq) {
          losses += seq - maxSeq - 1
          maxSeq = seq
          if (seq > limit * 0.9) {
            controller ! ((maxSeq, losses))
          }
        } else {
          controller ! s"Received out of order message. Previous: ${maxSeq} Received: ${seq}"
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

  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val rootB = RootActorPath(addressB)
  val here = {
    val path =
      system.actorSelection(rootB / "user" / "echo") ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  "AkkaProtocolTransport" must {
    "guarantee at-most-once delivery and message ordering despite packet loss" taggedAs TimingTest in {
      system.eventStream.publish(TestEvent.Mute(DeadLettersFilter[Any]))
      systemB.eventStream.publish(TestEvent.Mute(DeadLettersFilter[Any]))
      Await.result(RARP(system).provider.transport.managementCommand(One(addressB, Drop(0.3, 0.3))), 3.seconds.dilated)

      val tester = system.actorOf(Props(classOf[SequenceVerifier], here, self)) ! "start"

      expectMsgPF(60.seconds) {
        case (received: Int, lost: Int) ⇒
          log.debug(s" ######## Received ${received - lost} messages from ${received} ########")
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

  override def afterTermination(): Unit = shutdown(systemB)

}
