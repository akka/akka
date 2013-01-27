package akka.remote

import akka.testkit.AkkaSpec
import akka.actor.{ Props, ActorRef, Address }
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

      reg.writableEndpointWithPolicyFor(address1) must be === None

      reg.registerWritableEndpoint(address1, actorA) must be === actorA

      reg.writableEndpointWithPolicyFor(address1) must be === Some(Pass(actorA))
      reg.readOnlyEndpointFor(address1) must be === None
      reg.isWritable(actorA) must be(true)
      reg.isReadOnly(actorA) must be(false)

      reg.isQuarantined(address1) must be(false)
    }

    "be able to register a read-only endpoint" in {
      val reg = new EndpointRegistry
      reg.readOnlyEndpointFor(address1) must be === None

      reg.registerReadOnlyEndpoint(address1, actorA) must be === actorA

      reg.readOnlyEndpointFor(address1) must be === Some(actorA)
      reg.writableEndpointWithPolicyFor(address1) must be === None
      reg.isWritable(actorA) must be(false)
      reg.isReadOnly(actorA) must be(true)
      reg.isQuarantined(address1) must be(false)
    }

    "be able to register a writable and a read-only endpoint correctly" in {
      val reg = new EndpointRegistry
      reg.readOnlyEndpointFor(address1) must be === None
      reg.writableEndpointWithPolicyFor(address1) must be === None

      reg.registerReadOnlyEndpoint(address1, actorA) must be === actorA
      reg.registerWritableEndpoint(address1, actorB) must be === actorB

      reg.readOnlyEndpointFor(address1) must be === Some(actorA)
      reg.writableEndpointWithPolicyFor(address1) must be === Some(Pass(actorB))

      reg.isWritable(actorA) must be(false)
      reg.isWritable(actorB) must be(true)

      reg.isReadOnly(actorA) must be(true)
      reg.isReadOnly(actorB) must be(false)

    }

    "be able to register Gated policy for an address" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) must be === None
      reg.registerWritableEndpoint(address1, actorA)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.writableEndpointWithPolicyFor(address1) must be === Some(Gated(deadline))
      reg.isReadOnly(actorA) must be(false)
      reg.isWritable(actorA) must be(false)
    }

    "remove read-only endpoints if marked as failed" in {
      val reg = new EndpointRegistry

      reg.registerReadOnlyEndpoint(address1, actorA)
      reg.markAsFailed(actorA, Deadline.now)
      reg.readOnlyEndpointFor(address1) must be === None
    }

    "keep tombstones when removing an endpoint" in {
      val reg = new EndpointRegistry

      reg.registerWritableEndpoint(address1, actorA)
      reg.registerWritableEndpoint(address2, actorB)
      val deadline = Deadline.now
      reg.markAsFailed(actorA, deadline)
      reg.markAsQuarantined(address2, null)

      reg.unregisterEndpoint(actorA)
      reg.unregisterEndpoint(actorB)

      reg.writableEndpointWithPolicyFor(address1) must be === Some(Gated(deadline))
      reg.writableEndpointWithPolicyFor(address2) must be === Some(Quarantined(null))

    }

    "prune outdated Gated directives properly" in {
      val reg = new EndpointRegistry

      reg.registerWritableEndpoint(address1, actorA)
      reg.registerWritableEndpoint(address2, actorB)
      reg.markAsFailed(actorA, Deadline.now)
      val farInTheFuture = Deadline.now + Duration(60, SECONDS)
      reg.markAsFailed(actorB, farInTheFuture)
      reg.pruneGatedEntries()

      reg.writableEndpointWithPolicyFor(address1) must be === None
      reg.writableEndpointWithPolicyFor(address2) must be === Some(Gated(farInTheFuture))
    }

    "be able to register Quarantined policy for an address" in {
      val reg = new EndpointRegistry

      reg.writableEndpointWithPolicyFor(address1) must be === None
      reg.markAsQuarantined(address1, null)
      reg.isQuarantined(address1) must be(true)
      reg.writableEndpointWithPolicyFor(address1) must be === Some(Quarantined(null))
    }

  }

}
