package akka.remote.transport

import akka.actor.{ ExtendedActorSystem, Address }
import akka.remote.transport.AssociationHandle.Disassociated
import akka.remote.transport.AssociationHandle.InboundPayload
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport.Fail
import akka.remote.transport.Transport.InboundAssociation
import akka.remote.transport.Transport.Ready
import akka.remote.transport.Transport.Status
import akka.testkit.{ ImplicitSender, DefaultTimeout, AkkaSpec }
import akka.util.ByteString
import scala.concurrent.{ Future, Await }
import akka.remote.RemoteActorRefProvider

abstract class GenericTransportSpec(withAkkaProtocol: Boolean = false)
  extends AkkaSpec("""akka.actor.provider = "akka.remote.RemoteActorRefProvider" """)
  with DefaultTimeout with ImplicitSender {

  def transportName: String
  def schemeIdentifier: String

  val addressATest: Address = Address("test", "testsytemA", "testhostA", 4321)
  val addressBTest: Address = Address("test", "testsytemB", "testhostB", 5432)

  val addressA: Address = addressATest.copy(protocol = s"${addressATest.protocol}.$schemeIdentifier")
  val addressB: Address = addressBTest.copy(protocol = s"${addressBTest.protocol}.$schemeIdentifier")
  val nonExistingAddress = Address("test." + schemeIdentifier, "nosystem", "nohost", 0)

  def freshTransport(testTransport: TestTransport): Transport
  def wrapTransport(transport: Transport): Transport = if (withAkkaProtocol) {
    val provider = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]
    new AkkaProtocolTransport(transport, system, new AkkaProtocolSettings(provider.remoteSettings.config), AkkaPduProtobufCodec)
  } else transport

  def newTransportA(registry: AssociationRegistry): Transport =
    wrapTransport(freshTransport(new TestTransport(addressATest, registry)))
  def newTransportB(registry: AssociationRegistry): Transport =
    wrapTransport(freshTransport(new TestTransport(addressBTest, registry)))

  transportName must {

    "return an Address and promise when listen is called" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)

      val result = Await.result(transportA.listen, timeout.duration)

      result._1 must be(addressA)
      result._2 must not be null

      registry.logSnapshot.exists {
        case ListenAttempt(address) ⇒ address == addressATest
        case _                      ⇒ false
      } must be(true)
    }

    "associate successfully with another transport of its kind" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      // Must complete the returned promise to receive events
      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      transportA.associate(addressB)
      expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒
      }

      registry.logSnapshot.contains(AssociateAttempt(addressATest, addressBTest)) must be(true)
      awaitCond(registry.existsAssociation(addressATest, addressBTest))
    }

    "fail to associate with nonexisting address" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      awaitCond(registry.transportsReady(addressATest))

      Await.result(transportA.associate(nonExistingAddress), timeout.duration) match {
        case Fail(_) ⇒
        case _       ⇒ fail()
      }
    }

    "successfully send PDUs" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      val associate: Future[Status] = transportA.associate(addressB)
      val handleB = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒ handle
      }

      val Ready(handleA) = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(self)
      handleB.readHandlerPromise.success(self)

      val payload = ByteString("PDU")
      val pdu = if (withAkkaProtocol) AkkaPduProtobufCodec.constructPayload(payload) else payload

      awaitCond(registry.existsAssociation(addressATest, addressBTest))

      handleA.write(payload)
      expectMsgPF(timeout.duration, "Expect InboundPayload from A") {
        case InboundPayload(p) if payload == p ⇒
      }

      registry.logSnapshot.exists {
        case WriteAttempt(sender, recipient, sentPdu) ⇒
          sender == addressATest && recipient == addressBTest && sentPdu == pdu
        case _ ⇒ false
      } must be(true)
    }

    "successfully disassociate" in {
      val registry = new AssociationRegistry
      val transportA = newTransportA(registry)
      val transportB = newTransportB(registry)

      Await.result(transportA.listen, timeout.duration)._2.success(self)
      Await.result(transportB.listen, timeout.duration)._2.success(self)

      awaitCond(registry.transportsReady(addressATest, addressBTest))

      val associate: Future[Status] = transportA.associate(addressB)
      val handleB: AssociationHandle = expectMsgPF(timeout.duration, "Expect InboundAssociation from A") {
        case InboundAssociation(handle) if handle.remoteAddress == addressA ⇒ handle
      }

      val Ready(handleA) = Await.result(associate, timeout.duration)

      // Initialize handles
      handleA.readHandlerPromise.success(self)
      handleB.readHandlerPromise.success(self)

      awaitCond(registry.existsAssociation(addressATest, addressBTest))

      handleA.disassociate()

      expectMsgPF(timeout.duration) {
        case Disassociated ⇒
      }

      awaitCond(!registry.existsAssociation(addressATest, addressBTest))

      registry.logSnapshot exists {
        case DisassociateAttempt(requester, remote) if requester == addressATest && remote == addressBTest ⇒ true
        case _ ⇒ false
      } must be(true)
    }

  }
}