/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.transport

import akka.testkit.TimingTest
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.{ TimingTest, DefaultTimeout, ImplicitSender, AkkaSpec }
import com.typesafe.config.{ Config, ConfigFactory }
import AkkaProtocolStressTest._
import akka.actor._
import scala.concurrent.duration._
import akka.testkit._
import akka.remote.EndpointException
import akka.remote.{ RARP, EndpointException }
import akka.remote.transport.FailureInjectorTransportAdapter.{ One, All, Drop }
import scala.concurrent.Await
import akka.actor.ActorRef
import akka.actor.Actor
import akka.testkit.AkkaSpec
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ExtendedActorSystem
import akka.actor.RootActorPath
import akka.remote.transport.FailureInjectorTransportAdapter.One
import akka.remote.transport.FailureInjectorTransportAdapter.Drop
import akka.testkit.TestEvent
import akka.testkit.EventFilter
import akka.event.Logging
import akka.dispatch.sysmsg.{ Failed, SystemMessage }
import akka.pattern.pipe

object SystemMessageDeliveryStressTest {
  val baseConfig: Config = ConfigFactory parseString ("""
    akka {
      #loglevel = DEBUG
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote.log-remote-lifecycle-events = on

      remote.failure-detector {
        threshold = 1.0
        max-sample-size = 2
        min-std-deviation = 1 ms
        acceptable-heartbeat-pause = 0.01 s
      }
      remote.retry-window = 1 s
      remote.maximum-retries-in-window = 1000
      remote.use-passive-connections = on

      remote.netty.tcp {
        applied-adapters = ["gremlin"]
        port = 0
      }

    }
                                                   """)

  class SystemMessageSequenceVerifier(system: ActorSystem, testActor: ActorRef) extends MinimalActorRef {
    val provider = RARP(system).provider
    val path = provider.tempPath()

    RARP(system).provider.registerTempActor(this, path)

    override def getParent = provider.tempContainer

    override def sendSystemMessage(message: SystemMessage): Unit = {
      message match {
        case Failed(_, _, seq) ⇒ testActor ! seq
        case _                 ⇒
      }
    }
  }

  class SystemMessageSender(val msgCount: Int, val target: ActorRef) extends Actor {
    var counter = 0
    val targetRef = target.asInstanceOf[InternalActorRef]

    override def preStart(): Unit = self ! "sendnext"

    override def receive = {
      case "sendnext" ⇒
        targetRef.sendSystemMessage(Failed(null, null, counter))
        counter += 1
        if (counter < msgCount) self ! "sendnext"
    }
  }

}

abstract class SystemMessageDeliveryStressTest(msg: String, cfg: String)
  extends AkkaSpec(ConfigFactory.parseString(cfg).withFallback(configA)) with ImplicitSender with DefaultTimeout {
  import SystemMessageDeliveryStressTest._

  val systemB = ActorSystem("systemB", system.settings.config)
  val sysMsgVerifier = new SystemMessageSequenceVerifier(system, testActor)
  val MsgCount = 100

  val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val root = RootActorPath(address)
  // We test internals here (system message delivery) so we are allowed to cheat
  val there = RARP(systemB).provider.resolveActorRef(root / "temp" / sysMsgVerifier.path.name).asInstanceOf[InternalActorRef]

  override def atStartup() = {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*")))
    systemB.eventStream.publish(TestEvent.Mute(
      EventFilter[EndpointException](),
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*")))
  }

  "Remoting " + msg must {
    "guaranteed delivery and message ordering despite packet loss " taggedAs TimingTest in {
      Await.result(RARP(systemB).provider.transport.managementCommand(One(address, Drop(0.3, 0.3))), 3.seconds.dilated)
      systemB.actorOf(Props(classOf[SystemMessageSender], MsgCount, there))

      val toSend = (0 until MsgCount).toList
      val received = expectMsgAllOf(45.seconds, toSend: _*)

      received must be === toSend
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

class SystemMessageDeliveryDefault extends SystemMessageDeliveryStressTest("retry gate off, passive connections on", "")
class SystemMessageDeliveryRetryGate extends SystemMessageDeliveryStressTest("retry gate on, passive connections on",
  "akka.remote.retry-gate-closed-for = 0.5 s")
class SystemMessageDeliveryNoPassive extends SystemMessageDeliveryStressTest("retry gate off, passive connections off",
  "akka.remote.use-passive-connections = off")
class SystemMessageDeliveryNoPassiveRetryGate extends SystemMessageDeliveryStressTest("retry gate on, passive connections off",
  """
    akka.remote.use-passive-connections = off
    akka.remote.retry-gate-closed-for = 0.5 s
  """)
