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
      actor.serialize-messages = off
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote.log-remote-lifecycle-events = on

      remote.transport-failure-detector {
        threshold = 1.0
        max-sample-size = 2
        min-std-deviation = 1 ms
        ## We want lots of lost connections in this test, keep it sensitive
        heartbeat-interval = 1 s
        acceptable-heartbeat-pause = 1 s
      }
      ## Keep gate duration in this test for a low value otherwise too much messages are dropped
      remote.retry-gate-closed-for = 100 ms

      remote.netty.tcp {
        applied-adapters = ["gremlin"]
        port = 0
      }

    }

    akka.diagnostics.checker.disabled-checks += transport-failure-detector
    akka.diagnostics.checker.disabled-checks += retry-gate-closed-for
                                                   """)

  object ResendFinal

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
          // Due to the (bursty) lossyness of gate, we are happy with receiving at least one message from the upper
          // half (> 50000). Since messages are sent in bursts of 2000 0.5 seconds apart, this is reasonable.
          // The purpose of this test is not reliable delivery (there is a gremlin with 30% loss anyway) but respecting
          // the proper ordering.
          if (seq > limit * 0.5) {
            controller ! ((maxSeq, losses))
            context.system.scheduler.schedule(1.second, 1.second, self, ResendFinal)
            context.become(done)
          }
        } else {
          controller ! s"Received out of order message. Previous: ${maxSeq} Received: ${seq}"
        }
    }

    // Make sure the other side eventually "gets the message"
    def done: Receive = {
      case ResendFinal ⇒
        controller ! ((maxSeq, losses))
    }
  }

}

class AkkaProtocolStressTest extends AkkaSpec(configA) with ImplicitSender with DefaultTimeout {

  val systemB = ActorSystem("systemB", system.settings.config)
  val remote = systemB.actorOf(Props(new Actor {
    def receive = {
      case seq: Int ⇒ sender() ! seq
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
      Await.result(RARP(system).provider.transport.managementCommand(One(addressB, Drop(0.1, 0.1))), 3.seconds.dilated)

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
