/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._

import akka.actor.{ Address, Props }
import akka.remote.EndpointManager._
import akka.testkit.AkkaSpec

class EndpointRegistrySpec extends AkkaSpec {

  val actorA = system.actorOf(Props.empty, "actorA")
  val actorB = system.actorOf(Props.empty, "actorB")

  val address1 = Address("test", "testsys1", "testhost1", 1234)
  val address2 = Address("test", "testsys2", "testhost2", 1234)

  "EndpointRegistry" must {

    "be able to register a writable endpoint and policy" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) should ===(None)

      reg.registerWritableEndpoint(address1, None, actorA) should ===(actorA)

      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Pass(actorA, None)))
      reg.readOnlyEndpointFor(address1) should ===(None)
      reg.isWritable(actorA) should ===(true)
      reg.isReadOnly(actorA) should ===(false)

      reg.isQuarantined(address1, 42) should ===(false)
    }

    "be able to register a read-only endpoint" in {
      val reg = new EndpointRegistry
      reg.readOnlyEndpointFor(address1) should ===(None)

      reg.registerReadOnlyEndpoint(address1, actorA, 0) should ===(actorA)

      reg.readOnlyEndpointFor(address1) should ===(Some((actorA, 0)))
      reg.writableEndpointWithPolicyFor(address1) should be(None)
      reg.isWritable(actorA) should ===(false)
      reg.isReadOnly(actorA) should ===(true)
      reg.isQuarantined(address1, 42) should ===(false)
    }

    "be able to register a writable and a read-only endpoint correctly" in {
      val reg = new EndpointRegistry
      reg.readOnlyEndpointFor(address1) should ===(None)
      reg.writableEndpointWithPolicyFor(address1) should ===(None)

      reg.registerReadOnlyEndpoint(address1, actorA, 1) should ===(actorA)
      reg.registerWritableEndpoint(address1, None, actorB) should ===(actorB)

      reg.readOnlyEndpointFor(address1) should ===(Some((actorA, 1)))
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Pass(actorB, None)))

      reg.isWritable(actorA) should ===(false)
      reg.isWritable(actorB) should ===(true)

      reg.isReadOnly(actorA) should ===(true)
      reg.isReadOnly(actorB) should ===(false)

    }

    "be able to register Gated policy for an address" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) should ===(None)
      reg.registerWritableEndpoint(address1, None, actorA)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Gated(deadline)))
      reg.isReadOnly(actorA) should ===(false)
      reg.isWritable(actorA) should ===(false)
    }

    "remove read-only endpoints if marked as failed" in {
      val reg = new EndpointRegistry

      reg.registerReadOnlyEndpoint(address1, actorA, 2)
      reg.markAsFailed(actorA, Deadline.now)
      reg.readOnlyEndpointFor(address1) should ===(None)
    }

    "keep tombstones when removing an endpoint" in {
      val reg = new EndpointRegistry

      reg.registerWritableEndpoint(address1, None, actorA)
      reg.registerWritableEndpoint(address2, None, actorB)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.markAsQuarantined(address2, 42, deadline)

      reg.unregisterEndpoint(actorA)
      reg.unregisterEndpoint(actorB)

      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Gated(deadline)))
      reg.writableEndpointWithPolicyFor(address2) should ===(Some(Quarantined(42, deadline)))

    }

    "prune outdated Gated directives properly" in {
      val reg = new EndpointRegistry

      reg.registerWritableEndpoint(address1, None, actorA)
      reg.registerWritableEndpoint(address2, None, actorB)
      reg.markAsFailed(actorA, Deadline.now)
      val farInTheFuture = Deadline.now + Duration(60, SECONDS)
      reg.markAsFailed(actorB, farInTheFuture)
      reg.prune()

      reg.writableEndpointWithPolicyFor(address1) should ===(None)
      reg.writableEndpointWithPolicyFor(address2) should ===(Some(Gated(farInTheFuture)))
    }

    "be able to register Quarantined policy for an address" in {
      val reg = new EndpointRegistry
      val deadline = Deadline.now + 30.minutes

      reg.writableEndpointWithPolicyFor(address1) should ===(None)
      reg.markAsQuarantined(address1, 42, deadline)
      reg.isQuarantined(address1, 42) should ===(true)
      reg.isQuarantined(address1, 33) should ===(false)
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Quarantined(42, deadline)))
    }

    "keep refuseUid after register new endpoint" in {
      val reg = new EndpointRegistry
      val deadline = Deadline.now + 30.minutes

      reg.registerWritableEndpoint(address1, None, actorA)
      reg.markAsQuarantined(address1, 42, deadline)
      reg.refuseUid(address1) should ===(Some(42))
      reg.isQuarantined(address1, 42) should ===(true)

      reg.unregisterEndpoint(actorA)
      // Quarantined marker is kept so far
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Quarantined(42, deadline)))
      reg.refuseUid(address1) should ===(Some(42))
      reg.isQuarantined(address1, 42) should ===(true)

      reg.registerWritableEndpoint(address1, None, actorB)
      // Quarantined marker is gone
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Pass(actorB, None)))
      // but we still have the refuseUid
      reg.refuseUid(address1) should ===(Some(42))
      reg.isQuarantined(address1, 42) should ===(true)
    }

  }

}
