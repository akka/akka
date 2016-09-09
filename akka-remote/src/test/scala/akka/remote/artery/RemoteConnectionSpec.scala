/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.remote.RARP
import akka.testkit.SocketUtil._
import akka.testkit.{ AkkaSpec, EventFilter, ImplicitSender, TestActors, TestEvent, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class RemoteConnectionSpec extends ArteryMultiNodeSpec("akka.remote.retry-gate-closed-for = 5s") with ImplicitSender {

  def muteSystem(system: ActorSystem) {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*")))
  }

  "Remoting between systems" should {

    "be able to connect to system even if it's not there at first" in {
      muteSystem(localSystem)
      val localProbe = new TestProbe(localSystem)

      val remotePort = temporaryServerAddress(udp = true).getPort

      // try to talk to it before it is up
      val selection = localSystem.actorSelection(s"akka://$nextGeneratedSystemName@localhost:$remotePort/user/echo")
      selection.tell("ping", localProbe.ref)
      localProbe.expectNoMsg(1.seconds)

      // then start the remote system and try again
      val remoteSystem = newRemoteSystem(extraConfig = Some(s"akka.remote.artery.canonical.port=$remotePort"))

      muteSystem(remoteSystem)
      localProbe.expectNoMsg(2.seconds)
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")

      within(5.seconds) {
        awaitAssert {
          selection.tell("ping", localProbe.ref)
          localProbe.expectMsg(500.millis, "ping")
        }
      }
    }

    "allow other system to connect even if it's not there at first" in {
      val localSystem = newRemoteSystem()

      val localPort = port(localSystem)
      muteSystem(localSystem)

      val localProbe = new TestProbe(localSystem)
      localSystem.actorOf(TestActors.echoActorProps, "echo")

      val remotePort = temporaryServerAddress(udp = true).getPort

      // try to talk to remote before it is up
      val selection = localSystem.actorSelection(s"akka://$nextGeneratedSystemName@localhost:$remotePort/user/echo")
      selection.tell("ping", localProbe.ref)
      localProbe.expectNoMsg(1.seconds)

      // then when it is up, talk from other system
      val remoteSystem = newRemoteSystem(extraConfig = Some(s"akka.remote.artery.canonical.port=$remotePort"))

      muteSystem(remoteSystem)
      localProbe.expectNoMsg(2.seconds)
      val otherProbe = new TestProbe(remoteSystem)
      val otherSender = otherProbe.ref
      val thisSelection = remoteSystem.actorSelection(s"akka://${localSystem.name}@localhost:$localPort/user/echo")
      within(5.seconds) {
        awaitAssert {
          thisSelection.tell("ping", otherSender)
          otherProbe.expectMsg(500.millis, "ping")
        }
      }
    }
  }

}
