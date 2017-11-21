/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.RootActorPath
import akka.remote.RARP
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe
import org.scalatest.concurrent.Eventually
import scala.concurrent.duration._

class OutboundIdleShutdownSpec extends ArteryMultiNodeSpec("""
  akka.loglevel=DEBUG
  akka.remote.artery.advanced.stop-idle-outbound-after = 1 s
  """) with ImplicitSender with Eventually {

  "Outbound lanes" should {

    "eliminate an association when all of them are idle" in {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val localProbe = new TestProbe(localSystem)
      remoteEcho.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val artery = RARP(system).provider.transport.asInstanceOf[ArteryTransport]
      val association = artery.association(remoteAddress)
      withClue("When initiating a connection, both the control - and ordinary lanes are opened (regardless of which one was used)") {
        // TODO: Is this still desirable? Should lanes perhaps be opened opportunistically always?
        association.isStreamActive(Association.ControlQueueIndex) shouldBe true
        association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
      }

      eventually {
        association.isStreamActive(Association.ControlQueueIndex) shouldBe false
        association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe false

        // FIXME: Currently we have a memory leak in that associations are kept around even though the outbound channels are inactive
        artery.remoteAddresses shouldBe 'empty
      }
    }

    "have individual (in)active cycles" in {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val echoRef = remoteEcho.resolveOne(3.seconds).futureValue
      val localProbe = new TestProbe(localSystem)
      remoteEcho.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val artery = RARP(system).provider.transport.asInstanceOf[ArteryTransport]
      val association = artery.association(remoteAddress)

      withClue("When the ordinary lane is used and the control lane is not, the former should be active and the latter inactive") {
        eventually {
          remoteEcho.tell("ping", localProbe.ref)

          association.isStreamActive(Association.ControlQueueIndex) shouldBe false
          association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
        }
      }

      withClue("When the control lane is used and the ordinary lane is not, the former should be active and the latter inactive") {
        localProbe.watch(echoRef)
        eventually {
          association.isStreamActive(Association.ControlQueueIndex) shouldBe true
          association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe false
        }
      }
    }

    // FIXME test pending system messages

    "not deactivate if there are unacknowledged system messages" in {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val localProbe = new TestProbe(localSystem)
      remoteEcho.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val artery = RARP(system).provider.transport.asInstanceOf[ArteryTransport]
      val association = artery.association(remoteAddress)

      val associationState = association.associationState
      associationState.pendingSystemMessagesCount.incrementAndGet()
      association.isStreamActive(Association.ControlQueueIndex) shouldBe true

      Thread.sleep(3.seconds.toMillis)

      association.isStreamActive(Association.ControlQueueIndex) shouldBe true
    }

    "be dropped after the last outbound system message is acknowledged and the idle period has passed" in {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      val remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val localProbe = new TestProbe(localSystem)

      val association = RARP(system).provider.transport.asInstanceOf[ArteryTransport].association(remoteAddress)

      val echoRef = remoteEcho.resolveOne(3.seconds).futureValue
      localProbe.watch(echoRef)
      Thread.sleep(3.seconds.toMillis)
      association.isStreamActive(Association.ControlQueueIndex) shouldBe true

      localProbe.unwatch(echoRef)
      Thread.sleep(2000)
      eventually(association.associationState.pendingSystemMessagesCount.get() shouldBe 0)
      eventually(association.isStreamActive(Association.ControlQueueIndex) shouldBe false)
    }

    "still be resumable after the association has been cleaned" in {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val localProbe = new TestProbe(localSystem)
      remoteEcho.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val artery = RARP(system).provider.transport.asInstanceOf[ArteryTransport]
      val firstAssociation = artery.association(remoteAddress)

      eventually {
        firstAssociation.isActive() shouldBe false
        // FIXME: The memory leak actually plays up as the tests are now leaking as well (without the assertion below success ensues)
        artery.remoteAddresses shouldBe 'empty
      }

      withClue("re-initiating the connection should be the same as starting it the first time") {
        remoteEcho.tell("ping", localProbe.ref)
        localProbe.expectMsg("ping")
        val secondAssociation = artery.association(remoteAddress)

        secondAssociation.isStreamActive(Association.ControlQueueIndex) shouldBe true
        secondAssociation.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
      }
    }
  }
}
