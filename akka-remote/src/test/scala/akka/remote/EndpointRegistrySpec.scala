package akka.remote

import akka.testkit.AkkaSpec
import akka.actor.{ Props, Address }
import akka.remote.EndpointManager._
import scala.concurrent.duration._

class EndpointRegistrySpec extends AkkaSpec {

  val actorA = system.actorOf(Props.empty, "actorA")
  val actorB = system.actorOf(Props.empty, "actorB")

  val address1 = Address("test", "testsys1", "testhost1", 1234)
  val address2 = Address("test", "testsys2", "testhost2", 1234)

  "EndpointRegistry" must {

    "be able to register a writable endpoint and policy" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) should ===(None)

      reg.registerWritableEndpoint(address1, None, None, actorA) should ===(actorA)

      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Pass(actorA, None, None)))
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
      reg.registerWritableEndpoint(address1, None, None, actorB) should ===(actorB)

      reg.readOnlyEndpointFor(address1) should ===(Some((actorA, 1)))
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Pass(actorB, None, None)))

      reg.isWritable(actorA) should ===(false)
      reg.isWritable(actorB) should ===(true)

      reg.isReadOnly(actorA) should ===(true)
      reg.isReadOnly(actorB) should ===(false)

    }

    "be able to register Gated policy for an address" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) should ===(None)
      reg.registerWritableEndpoint(address1, None, None, actorA)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Gated(deadline, None)))
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

      reg.registerWritableEndpoint(address1, None, None, actorA)
      reg.registerWritableEndpoint(address2, None, None, actorB)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.markAsQuarantined(address2, 42, deadline)

      reg.unregisterEndpoint(actorA)
      reg.unregisterEndpoint(actorB)

      reg.writableEndpointWithPolicyFor(address1) should ===(Some(Gated(deadline, None)))
      reg.writableEndpointWithPolicyFor(address2) should ===(Some(Quarantined(42, deadline)))

    }

    "prune outdated Gated directives properly" in {
      val reg = new EndpointRegistry

      reg.registerWritableEndpoint(address1, None, None, actorA)
      reg.registerWritableEndpoint(address2, None, None, actorB)
      reg.markAsFailed(actorA, Deadline.now)
      val farInTheFuture = Deadline.now + Duration(60, SECONDS)
      reg.markAsFailed(actorB, farInTheFuture)
      reg.prune()

      reg.writableEndpointWithPolicyFor(address1) should ===(Some(WasGated(None)))
      reg.writableEndpointWithPolicyFor(address2) should ===(Some(Gated(farInTheFuture, None)))
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

  }

}
