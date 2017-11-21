/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.RootActorPath
import akka.remote.RARP
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe

class OutboundIdleShutdownSpec extends ArteryMultiNodeSpec("""
  akka.loglevel=DEBUG
  akka.remote.artery.advanced.stop-idle-outbound-after = 3 s
  """) with ImplicitSender {

  "Idle outbound associations" should {

    "be cleaned up" in {

      val remoteSystem = newRemoteSystem()
      remoteSystem.actorOf(TestActors.echoActorProps, "echo")
      val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

      def sel = system.actorSelection(RootActorPath(remoteAddress) / "user" / "echo")
      val localProbe = new TestProbe(localSystem)
      sel.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")

      val accociation = RARP(system).provider.transport.asInstanceOf[ArteryTransport].association(remoteAddress)
      accociation.isStreamActive(Association.ControlQueueIndex) should ===(true)
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(true)

      // FIXME better way of testing this

      Thread.sleep(5000)
      println(s"# sleep 1 end") // FIXME
      accociation.isStreamActive(Association.ControlQueueIndex) should ===(false)
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(false)

      Thread.sleep(5000)
      println(s"# sleep 2 end") // FIXME
      accociation.isStreamActive(Association.ControlQueueIndex) should ===(false)
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(false)

      sel.tell("ping", localProbe.ref)
      localProbe.expectMsg("ping")
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(true)

      Thread.sleep(5000)
      println(s"# sleep 3 end") // FIXME
      accociation.isStreamActive(Association.ControlQueueIndex) should ===(false)
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(false)

      Thread.sleep(5000)
      println(s"# sleep 4 end") // FIXME
      accociation.isStreamActive(Association.ControlQueueIndex) should ===(false)
      accociation.isStreamActive(Association.OrdinaryQueueIndex) should ===(false)

    }

    // FIXME test pending system messages

  }

}
