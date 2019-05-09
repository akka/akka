/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import akka.actor.{ RootActorPath, _ }
import akka.event.Logging.Warning
import akka.remote.{ QuarantinedEvent, RARP, RemoteActorRef }
import akka.testkit.{ SocketUtil, _ }
import com.github.ghik.silencer.silent
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class RemoteDeathWatchSpec
    extends AkkaSpec(ConfigFactory.parseString("""
akka {
    actor {
        provider = remote
        deployment {
            /watchers.remote = "akka.tcp://other@localhost:2666"
        }
    }
    remote.artery.enabled = off
    remote.classic {
      retry-gate-closed-for = 1 s
      initial-system-message-delivery-timeout = 3 s
      netty.tcp {
        hostname = "localhost"
        port = 0
      }
    }
}
"""))
    with ImplicitSender
    with DefaultTimeout
    with DeathWatchSpec {

  val protocol =
    if (RARP(system).provider.remoteSettings.Artery.Enabled) "akka"
    else "akka.tcp"

  val other = ActorSystem(
    "other",
    ConfigFactory.parseString("""
        akka.loglevel = DEBUG
        akka.remote.artery.enabled = off
        akka.remote.classic.netty.tcp.port=2666
                              """).withFallback(system.settings.config))

  override def beforeTermination(): Unit = {
    system.eventStream.publish(TestEvent.Mute(EventFilter.warning(pattern = "received dead letter.*Disassociate")))
  }

  override def afterTermination(): Unit = {
    shutdown(other)
  }

  override def expectedTestDuration: FiniteDuration = 120.seconds

  "receive Terminated when system of de-serialized ActorRef is not running" in {
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[QuarantinedEvent])
    val rarp = RARP(system).provider
    // pick an unused port
    val port = SocketUtil.temporaryLocalPort()
    // simulate de-serialized ActorRef
    val ref = rarp.resolveActorRef(s"$protocol://OtherSystem@localhost:$port/user/foo/bar#1752527294")
    system.actorOf(Props(new Actor {
      context.watch(ref)
      def receive = {
        case Terminated(r) => testActor ! r
      }
    }).withDeploy(Deploy.local))

    expectMsg(20.seconds, ref)
    // we don't expect real quarantine when the UID is unknown, i.e. QuarantinedEvent is not published
    probe.expectNoMessage(3.seconds)
    // The following verifies ticket #3870, i.e. make sure that re-delivery of Watch message is stopped.
    // It was observed as periodic logging of "address is now gated" when the gate was lifted.
    system.eventStream.subscribe(probe.ref, classOf[Warning])
    probe.expectNoMessage(rarp.remoteSettings.RetryGateClosedFor * 2)
  }

  "receive Terminated when watched node is unknown host" in {
    val path = RootActorPath(Address(protocol, system.name, "unknownhost", 2552)) / "user" / "subject"

    system.actorOf(Props(new Actor {
      @silent
      val watchee = RARP(context.system).provider.resolveActorRef(path)
      context.watch(watchee)

      def receive = {
        case t: Terminated => testActor ! t.actor.path
      }
    }).withDeploy(Deploy.local), name = "observer2")

    expectMsg(60.seconds, path)
  }

  "receive ActorIdentity(None) when identified node is unknown host" in {
    val path = RootActorPath(Address(protocol, system.name, "unknownhost2", 2552)) / "user" / "subject"
    system.actorSelection(path) ! Identify(path)
    expectMsg(60.seconds, ActorIdentity(path, None))
  }

  "quarantine systems after unsuccessful system message delivery if have not communicated before" in {
    // Synthesize an ActorRef to a remote system this one has never talked to before.
    // This forces ReliableDeliverySupervisor to start with unknown remote system UID.
    val extinctPath = RootActorPath(Address(protocol, "extinct-system", "localhost", SocketUtil.temporaryLocalPort())) / "user" / "noone"
    val transport = RARP(system).provider.transport
    val extinctRef = new RemoteActorRef(
      transport,
      transport.localAddressForRemote(extinctPath.address),
      extinctPath,
      Nobody,
      props = None,
      deploy = None)

    val probe = TestProbe()
    probe.watch(extinctRef)
    probe.unwatch(extinctRef)

    probe.expectNoMessage(5.seconds)
    system.eventStream.subscribe(probe.ref, classOf[Warning])
    probe.expectNoMessage(RARP(system).provider.remoteSettings.RetryGateClosedFor * 2)
  }

}
