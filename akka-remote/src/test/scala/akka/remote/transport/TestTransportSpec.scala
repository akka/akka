package akka.remote.transport

import akka.testkit._
import scala.concurrent._
import akka.actor.Address
import akka.remote.transport.Transport._
import akka.remote.transport.TestTransport._
import akka.util.ByteString
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }

class TestTransportSpec extends AkkaSpec with DefaultTimeout with ImplicitSender {

  val addressA: Address = Address("akka", "testsytemA", "testhostA", 4321)
  val addressB: Address = Address("akka", "testsytemB", "testhostB", 5432)
  val nonExistingAddress = Address("akka", "nosystem", "nohost", 0)

  "TestTransport" must {

    "return an Address and promise when listen is called and log calls" in {
      val registry = new AssociationRegistry
      var transportA = new TestTransport(addressA, registry)

      val result = Await.result(transportA.listen, timeout.duration)

      result._1 must be(addressA)
      result._2 must not be null

      registry.logSnapshot.exists {
        case ListenAttempt(address) ⇒ address == addressA
        case _                      ⇒ false
      } must be(true)
    }

    "associate successfully with another TestTransport and log" in {
      val registry = new AssociationRegistry
      var transportA = new TestTransport(addressA, registry)
      var transportB = new TestTransport(addressB, registry)

      // Must complete the returned promise to receive events
      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(transportA, transportB))

      transportA.associate(addressB)
      expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒
      }

      registry.logSnapshot.contains(AssociateAttempt(addressA, addressB)) must be(true)
    }

    "fail to associate with nonexisting address" in {
      val registry = new AssociationRegistry
      var transportA = new TestTransport(addressA, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportA.associate(nonExistingAddress), timeout.duration) match {
        case Fail(_) ⇒
        case _       ⇒ fail()
      }
    }

    "emulate sending PDUs and logs write" in {
      val registry = new AssociationRegistry
      var transportA = new TestTransport(addressA, registry)
      var transportB = new TestTransport(addressB, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(transportA, transportB))

      val associate: Future[Status] = transportA.associate(addressB)
      val handleB = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒ handle
      }

      val Ready(handleA) = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(self)
      handleB.readHandlerPromise.success(self)

      val akkaPDU = ByteString("AkkaPDU")

      awaitCond(registry.existsAssociation(addressA, addressB))

      handleA.write(akkaPDU)
      expectMsgPF(timeout.duration, "Expect InboundPayload from A") {
        case InboundPayload(payload) if payload == akkaPDU ⇒
      }

      registry.logSnapshot.exists {
        case WriteAttempt(sender, recipient, payload) ⇒
          sender == addressA && recipient == addressB && payload == akkaPDU
        case _ ⇒ false
      } must be(true)
    }

    "emulate disassociation and log it" in {
      val registry = new AssociationRegistry
      var transportA = new TestTransport(addressA, registry)
      var transportB = new TestTransport(addressB, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(transportA, transportB))

      val associate: Future[Status] = transportA.associate(addressB)
      val handleB: AssociationHandle = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒ handle
      }

      val Ready(handleA) = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(self)
      handleB.readHandlerPromise.success(self)

      awaitCond(registry.existsAssociation(addressA, addressB))

      handleA.disassociate()

      expectMsgPF(timeout.duration) {
        case Disassociated ⇒
      }

      awaitCond(!registry.existsAssociation(addressA, addressB))

      registry.logSnapshot exists {
        case DisassociateAttempt(requester, remote) if requester == addressA && remote == addressB ⇒ true
        case _ ⇒ false
      } must be(true)
    }

  }

}
