/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic.transport

import scala.concurrent.Await
import scala.concurrent.duration._

import scala.annotation.nowarn
import com.typesafe.config.{ Config, ConfigFactory }

import akka.actor.{ Actor, ActorRef, ActorSystem, ExtendedActorSystem, Props, RootActorPath, _ }
import akka.dispatch.sysmsg.{ Failed, SystemMessage }
import akka.remote.{ EndpointException, QuarantinedEvent, RARP }
import akka.remote.transport.AssociationHandle
import akka.remote.transport.FailureInjectorTransportAdapter.{ Drop, One }
import akka.remote.transport.ThrottlerTransportAdapter._
import akka.testkit.{ AkkaSpec, DefaultTimeout, EventFilter, ImplicitSender, TestEvent, TimingTest, _ }

object SystemMessageDeliveryStressTest {
  val msgCount = 5000
  val burstSize = 100
  val burstDelay = 500.millis

  val baseConfig: Config = ConfigFactory.parseString(s"""
    akka {
      #loglevel = DEBUG
      remote.artery.enabled = false
      actor.provider = remote
      # test is using Java serialization and not priority to rewrite
      actor.allow-java-serialization = on
      actor.warn-about-java-serializer-usage = off

      remote.classic {
        log-remote-lifecycle-events = on
        system-message-buffer-size = $msgCount
        resend-interval = 2 s
        use-passive-connections = on
        initial-system-message-delivery-timeout = 10 m
        ## Keep this setting tight, otherwise the test takes a long time or times out
        system-message-ack-piggyback-timeout = 100 ms // Force heavy Ack traffic
        
        transport-failure-detector {
          heartbeat-interval = 1 s
          acceptable-heartbeat-pause = 5 s
       }
      }

      

      remote.classic.netty.tcp {
        applied-adapters = ["gremlin", "trttl"]
        port = 0
      }

    }
                                                   """)

  private[akka] class SystemMessageSequenceVerifier(system: ActorSystem, testActor: ActorRef) extends MinimalActorRef {
    val provider = RARP(system).provider
    val path = provider.tempPath()

    RARP(system).provider.registerTempActor(this, path)

    override def getParent = provider.tempContainer

    override def sendSystemMessage(message: SystemMessage): Unit = {
      message match {
        case Failed(_, _, seq) => testActor ! seq
        case _                 =>
      }
    }
  }

  class SystemMessageSender(val msgCount: Int, val burstSize: Int, val burstDelay: FiniteDuration, val target: ActorRef)
      extends Actor {
    import context.dispatcher

    var counter = 0
    var burstCounter = 0
    val targetRef = target.asInstanceOf[InternalActorRef]
    val child = context.actorOf(Props.empty, "failedChild") // need a dummy ActorRef

    override def preStart(): Unit = self ! "sendnext"

    override def receive = {
      case "sendnext" =>
        targetRef.sendSystemMessage(Failed(child, null, counter))
        counter += 1
        burstCounter += 1

        if (counter < msgCount) {
          if (burstCounter < burstSize) self ! "sendnext"
          else {
            burstCounter = 0
            context.system.scheduler.scheduleOnce(burstDelay, self, "sendnext")
          }
        }
    }
  }

}

@nowarn("msg=deprecated")
abstract class SystemMessageDeliveryStressTest(msg: String, cfg: String)
    extends AkkaSpec(ConfigFactory.parseString(cfg).withFallback(SystemMessageDeliveryStressTest.baseConfig))
    with ImplicitSender
    with DefaultTimeout {
  import SystemMessageDeliveryStressTest._

  override def expectedTestDuration: FiniteDuration = 200.seconds

  val systemA = system
  val systemB = ActorSystem("systemB", system.settings.config)

  val probeA = TestProbe()(systemA)
  val probeB = TestProbe()(systemB)

  val sysMsgVerifierA = new SystemMessageSequenceVerifier(systemA, probeA.ref)
  val sysMsgVerifierB = new SystemMessageSequenceVerifier(systemB, probeB.ref)

  val addressA = systemA.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  // We test internals here (system message delivery) so we are allowed to cheat
  val targetForA = RARP(systemA).provider.resolveActorRef(RootActorPath(addressB) / "temp" / sysMsgVerifierB.path.name)
  val targetForB = RARP(systemB).provider.resolveActorRef(RootActorPath(addressA) / "temp" / sysMsgVerifierA.path.name)

  override def atStartup() = {
    systemA.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead .*")))
    systemB.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead .*")))

    systemA.eventStream.subscribe(probeA.ref, classOf[QuarantinedEvent])
    systemB.eventStream.subscribe(probeB.ref, classOf[QuarantinedEvent])
  }

  "Remoting " + msg must {
    "guaranteed delivery and message ordering despite packet loss " taggedAs TimingTest in {
      import systemA.dispatcher

      val transportA = RARP(systemA).provider.transport
      val transportB = RARP(systemB).provider.transport

      Await.result(transportA.managementCommand(One(addressB, Drop(0.1, 0.1))), 3.seconds.dilated)
      Await.result(transportB.managementCommand(One(addressA, Drop(0.1, 0.1))), 3.seconds.dilated)

      // Schedule peridodic disassociates
      systemA.scheduler.scheduleWithFixedDelay(3.second, 8.seconds) { () =>
        transportA.managementCommand(ForceDisassociateExplicitly(addressB, reason = AssociationHandle.Unknown))
      }

      systemB.scheduler.scheduleWithFixedDelay(7.seconds, 8.seconds) { () =>
        transportB.managementCommand(ForceDisassociateExplicitly(addressA, reason = AssociationHandle.Unknown))
      }

      systemB.actorOf(Props(classOf[SystemMessageSender], msgCount, burstSize, burstDelay, targetForB))
      systemA.actorOf(Props(classOf[SystemMessageSender], msgCount, burstSize, burstDelay, targetForA))

      var maxDelay = 0L

      for (m <- 0 until msgCount) {
        val start = System.currentTimeMillis()
        probeB.expectMsg(10.minutes, m)
        probeA.expectMsg(10.minutes, m)
        maxDelay = math.max(maxDelay, (System.currentTimeMillis() - start) / 1000)
      }
    }
  }

  override def beforeTermination(): Unit = {
    system.eventStream.publish(
      TestEvent.Mute(
        EventFilter.warning(source = s"akka://AkkaProtocolStressTest/user/$$a", start = "received dead letter"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    systemB.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
  }

  override def afterTermination(): Unit = shutdown(systemB)

}

class SystemMessageDeliveryRetryGate
    extends SystemMessageDeliveryStressTest(
      "passive connections on",
      """akka.remote.classic.retry-gate-closed-for = 0.5 s""")
class SystemMessageDeliveryNoPassiveRetryGate
    extends SystemMessageDeliveryStressTest("passive connections off", """
    akka.remote.classic.use-passive-connections = off
    akka.remote.classic.retry-gate-closed-for = 0.5 s
  """)
