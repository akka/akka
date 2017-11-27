/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ ActorRef, Address, RootActorPath }
import akka.remote.RARP
import akka.testkit.{ ImplicitSender, TestActors, TestProbe }
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

class OutboundIdleShutdownSpec extends ArteryMultiNodeSpec("""
  akka.loglevel=DEBUG
  akka.remote.artery.advanced.stop-idle-outbound-after = 1 s
  """) with ImplicitSender with Eventually {

  "Outbound streams" should {

    "eliminate an association when all streams within are idle" in withAssociation {
      (remoteAddress, remoteEcho, localArtery, localProbe) ⇒

        val association = localArtery.association(remoteAddress)
        withClue("When initiating a connection, both the control - and ordinary streams are opened (regardless of which one was used)") {
          association.isStreamActive(Association.ControlQueueIndex) shouldBe true
          association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
        }

        eventually {
          association.isStreamActive(Association.ControlQueueIndex) shouldBe false
          association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe false

          // FIXME: Currently we have a memory leak in that associations are kept around even though the outbound channels are inactive
          localArtery.remoteAddresses shouldBe 'empty
        }
    }

    "have individual (in)active cycles" in withAssociation {
      (remoteAddress, remoteEcho, localArtery, localProbe) ⇒

        val association = localArtery.association(remoteAddress)

        withClue("When the ordinary stream is used and the control stream is not, the former should be active and the latter inactive") {
          eventually {
            remoteEcho.tell("ping", localProbe.ref)
            association.isStreamActive(Association.ControlQueueIndex) shouldBe false
            association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
          }
        }

        withClue("When the control stream is used and the ordinary stream is not, the former should be active and the latter inactive") {
          localProbe.watch(remoteEcho)
          eventually {
            association.isStreamActive(Association.ControlQueueIndex) shouldBe true
            association.isStreamActive(Association.OrdinaryQueueIndex) shouldBe false
          }
        }
    }

    "not deactivate if there are unacknowledged system messages" in withAssociation {
      (remoteAddress, remoteEcho, localArtery, localProbe) ⇒
        val association = localArtery.association(remoteAddress)

        val associationState = association.associationState
        associationState.pendingSystemMessagesCount.incrementAndGet()
        association.isStreamActive(Association.ControlQueueIndex) shouldBe true

        Thread.sleep(3.seconds.toMillis)
        association.isStreamActive(Association.ControlQueueIndex) shouldBe true
    }

    "be dropped after the last outbound system message is acknowledged and the idle period has passed" in withAssociation {
      (remoteAddress, remoteEcho, localArtery, localProbe) ⇒

        val association = RARP(system).provider.transport.asInstanceOf[ArteryTransport].association(remoteAddress)

        association.associationState.pendingSystemMessagesCount.incrementAndGet()
        Thread.sleep(3.seconds.toMillis)
        association.isStreamActive(Association.ControlQueueIndex) shouldBe true

        association.associationState.pendingSystemMessagesCount.decrementAndGet()
        Thread.sleep(3.seconds.toMillis)
        eventually(association.isStreamActive(Association.ControlQueueIndex) shouldBe false)
    }

    "still be resumable after the association has been cleaned" in withAssociation {
      (remoteAddress, remoteEcho, localArtery, localProbe) ⇒
        val firstAssociation = localArtery.association(remoteAddress)

        eventually {
          firstAssociation.isActive() shouldBe false
          // FIXME: The memory leak actually plays up as the tests are now leaking as well (without the assertion below success ensues)
          localArtery.remoteAddresses shouldBe 'empty
        }

        withClue("re-initiating the connection should be the same as starting it the first time") {
          remoteEcho.tell("ping", localProbe.ref)
          localProbe.expectMsg("ping")
          val secondAssociation = localArtery.association(remoteAddress)

          secondAssociation.isStreamActive(Association.ControlQueueIndex) shouldBe true
          secondAssociation.isStreamActive(Association.OrdinaryQueueIndex) shouldBe true
        }
    }

    /**
     * Test setup fixture:
     * 1. A 'remote' ActorSystem is created to spawn an Echo actor,
     * 2. A TestProbe is spawned locally to initiate communication with the Echo actor
     * 3. Details (remoteAddress, remoteEcho, localArtery, localProbe) are supplied to the test
     */
    def withAssociation(test: (Address, ActorRef, ArteryTransport, TestProbe) ⇒ Any): Unit = {
      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def remoteEcho = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")

      val echoRef = remoteEcho.resolveOne(3.seconds).futureValue
      val localProbe = new TestProbe(localSystem)

      remoteEcho.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val artery = RARP(system).provider.transport.asInstanceOf[ArteryTransport]

      test(remoteAddress, echoRef, artery, localProbe)

      remoteSystem.terminate()
    }
  }
}
