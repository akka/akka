/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic.transport

import scala.concurrent._

import scala.annotation.nowarn

import akka.actor.Address
import akka.remote.transport.{ AssociationHandle, TestTransport }
import akka.remote.transport.AssociationHandle.{ ActorHandleEventListener, Disassociated, InboundPayload }
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport._
import akka.testkit._
import akka.util.ByteString

@nowarn("msg=deprecated")
class TestTransportSpec extends AkkaSpec with DefaultTimeout with ImplicitSender {

  val addressA: Address = Address("test", "testsytemA", "testhostA", 4321)
  val addressB: Address = Address("test", "testsytemB", "testhostB", 5432)
  val nonExistingAddress = Address("test", "nosystem", "nohost", 0)

  "TestTransport" must {

    "return an Address and promise when listen is called and log calls" in {
      val registry = new AssociationRegistry
      val transportA = new TestTransport(addressA, registry)

      val result = Await.result(transportA.listen, timeout.duration)

      result._1 should ===(addressA)
      result._2 should not be null

      registry.logSnapshot.exists {
        case ListenAttempt(address) => address == addressA
        case _                      => false
      } should ===(true)
    }

    "associate successfully with another TestTransport and log" in {
      val registry = new AssociationRegistry
      val transportA = new TestTransport(addressA, registry)
      val transportB = new TestTransport(addressB, registry)

      // Must complete the returned promise to receive events
      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressA, addressB))

      transportA.associate(addressB)
      expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA =>
      }

      registry.logSnapshot.contains(AssociateAttempt(addressA, addressB)) should ===(true)
    }

    "fail to associate with nonexisting address" in {
      val registry = new AssociationRegistry
      val transportA = new TestTransport(addressA, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      // TestTransport throws IllegalAssociationException when trying to associate with non-existing system
      intercept[InvalidAssociationException] {
        Await.result(transportA.associate(nonExistingAddress), timeout.duration)
      }

    }

    "emulate sending PDUs and logs write" in {
      val registry = new AssociationRegistry
      val transportA = new TestTransport(addressA, registry)
      val transportB = new TestTransport(addressB, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressA, addressB))

      val associate: Future[AssociationHandle] = transportA.associate(addressB)
      val handleB = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA => handle
      }

      handleB.readHandlerPromise.success(ActorHandleEventListener(self))
      val handleA = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(ActorHandleEventListener(self))

      val akkaPDU = ByteString("AkkaPDU")

      awaitCond(registry.existsAssociation(addressA, addressB))

      handleA.write(akkaPDU)
      expectMsgPF(timeout.duration, "Expect InboundPayload from A") {
        case InboundPayload(payload) if payload == akkaPDU =>
      }

      registry.logSnapshot.exists {
        case WriteAttempt(sender, recipient, payload) =>
          sender == addressA && recipient == addressB && payload == akkaPDU
        case _ => false
      } should ===(true)
    }

    "emulate disassociation and log it" in {
      val registry = new AssociationRegistry
      val transportA = new TestTransport(addressA, registry)
      val transportB = new TestTransport(addressB, registry)

      Await.result(transportA.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))
      Await.result(transportB.listen, timeout.duration)._2.success(ActorAssociationEventListener(self))

      awaitCond(registry.transportsReady(addressA, addressB))

      val associate: Future[AssociationHandle] = transportA.associate(addressB)
      val handleB: AssociationHandle = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA => handle
      }

      handleB.readHandlerPromise.success(ActorHandleEventListener(self))
      val handleA = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(ActorHandleEventListener(self))

      awaitCond(registry.existsAssociation(addressA, addressB))

      handleA.disassociate("Test disassociation", log)

      expectMsgPF(timeout.duration) {
        case Disassociated(_) =>
      }

      awaitCond(!registry.existsAssociation(addressA, addressB))

      registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) if requester == addressA && remote == addressB => true
        case _                                                                                     => false
      } should ===(true)
    }

  }

}
