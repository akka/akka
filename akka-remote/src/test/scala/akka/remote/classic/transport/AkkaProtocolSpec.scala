/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic.transport

import java.util.concurrent.TimeoutException

import akka.actor.Address
import akka.protobufv3.internal.{ ByteString => PByteString }
import akka.remote.classic.transport.AkkaProtocolSpec.TestFailureDetector
import akka.remote.transport.AkkaPduCodec.{ Associate, Disassociate, Heartbeat }
import akka.remote.transport.AssociationHandle.{
  ActorHandleEventListener,
  DisassociateInfo,
  Disassociated,
  InboundPayload
}
import akka.remote.transport.ProtocolStateActor
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport._
import akka.remote.transport.{ AssociationRegistry => _, _ }
import akka.remote.{ FailureDetector, WireFormats }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.util.{ ByteString, OptionVal }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

import com.github.ghik.silencer.silent

object AkkaProtocolSpec {

  class TestFailureDetector extends FailureDetector {
    @volatile var isAvailable: Boolean = true

    def isMonitoring: Boolean = called

    @volatile var called: Boolean = false

    def heartbeat(): Unit = called = true
  }

}

@silent("deprecated")
class AkkaProtocolSpec extends AkkaSpec("""akka.actor.provider = remote """) with ImplicitSender {

  val conf = ConfigFactory.parseString("""
      akka.remote {

        
        
        classic {
          backoff-interval = 1 s
          shutdown-timeout = 5 s
          startup-timeout = 5 s
          use-passive-connections = on
          transport-failure-detector {
            implementation-class = "akka.remote.PhiAccrualFailureDetector"
            max-sample-size = 100
            min-std-deviation = 100 ms
            acceptable-heartbeat-pause = 3 s
            heartbeat-interval = 1 s
          }
        }

      }
      # test is using Java serialization and not priority to rewrite
      akka.actor.allow-java-serialization = on
      akka.actor.warn-about-java-serializer-usage = off
  """).withFallback(system.settings.config)

  val localAddress = Address("test", "testsystem", "testhost", 1234)
  val localAkkaAddress = Address("akka.test", "testsystem", "testhost", 1234)

  val remoteAddress = Address("test", "testsystem2", "testhost2", 1234)
  val remoteAkkaAddress = Address("akka.test", "testsystem2", "testhost2", 1234)

  val codec = AkkaPduProtobufCodec

  val testMsg =
    WireFormats.SerializedMessage.newBuilder().setSerializerId(0).setMessage(PByteString.copyFromUtf8("foo")).build
  val testEnvelope = codec.constructMessage(localAkkaAddress, testActor, testMsg, OptionVal.None)
  val testMsgPdu: ByteString = codec.constructPayload(testEnvelope)

  def testHeartbeat = InboundPayload(codec.constructHeartbeat)
  def testPayload = InboundPayload(testMsgPdu)

  def testDisassociate(info: DisassociateInfo) = InboundPayload(codec.constructDisassociate(info))
  def testAssociate(uid: Int) =
    InboundPayload(codec.constructAssociate(HandshakeInfo(remoteAkkaAddress, uid)))

  def collaborators = {
    val registry = new AssociationRegistry
    val transport: TestTransport = new TestTransport(localAddress, registry)
    val handle: TestAssociationHandle = TestAssociationHandle(localAddress, remoteAddress, transport, true)

    // silently drop writes -- we do not have another endpoint under test, so nobody to forward to
    transport.writeBehavior.pushConstant(true)
    (new TestFailureDetector, registry, transport, handle)
  }

  def lastActivityIsHeartbeat(registry: AssociationRegistry) =
    if (registry.logSnapshot.isEmpty) false
    else
      registry.logSnapshot.last match {
        case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress =>
          codec.decodePdu(payload) match {
            case Heartbeat => true
            case _         => false
          }
        case _ => false
      }

  def lastActivityIsAssociate(registry: AssociationRegistry, uid: Long) =
    if (registry.logSnapshot.isEmpty) false
    else
      registry.logSnapshot.last match {
        case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress =>
          codec.decodePdu(payload) match {
            case Associate(info) =>
              info.origin == localAddress && info.uid == uid
            case _ => false
          }
        case _ => false
      }

  def lastActivityIsDisassociate(registry: AssociationRegistry) =
    if (registry.logSnapshot.isEmpty) false
    else
      registry.logSnapshot.last match {
        case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress =>
          codec.decodePdu(payload) match {
            case Disassociate(_) => true
            case _               => false
          }
        case _ => false
      }

  "ProtocolStateActor" must {

    "register itself as reader on injecteted handles" in {
      val (failureDetector, _, _, handle) = collaborators

      system.actorOf(
        ProtocolStateActor.inboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          handle,
          ActorAssociationEventListener(testActor),
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector))

      awaitCond(handle.readHandlerPromise.isCompleted)
    }

    "in inbound mode accept payload after Associate PDU received" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(
        ProtocolStateActor.inboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          handle,
          ActorAssociationEventListener(testActor),
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector))

      reader ! testAssociate(uid = 33)

      awaitCond(failureDetector.called)

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h: AkkaProtocolHandle) =>
          h.handshakeInfo.uid should ===(33)
          h
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      failureDetector.called should ===(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))

      reader ! testPayload

      expectMsgPF() {
        case InboundPayload(p) => p should ===(testEnvelope)
      }
    }

    "in inbound mode disassociate when an unexpected message arrives instead of Associate" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(
        ProtocolStateActor.inboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          handle,
          ActorAssociationEventListener(testActor),
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector))

      // a stray message will force a disassociate
      reader ! testHeartbeat

      // this associate will now be ignored
      reader ! testAssociate(uid = 33)

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(_, _) => true
        case _                         => false
      })
    }

    "in outbound mode delay readiness until handshake finished" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector,
          refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, 42))
      awaitCond(failureDetector.called)

      // keeps sending heartbeats
      awaitCond(lastActivityIsHeartbeat(registry))

      statusPromise.isCompleted should ===(false)

      // finish connection by sending back an associate message
      reader ! testAssociate(33)

      Await.result(statusPromise.future, 3.seconds) match {
        case h: AkkaProtocolHandle =>
          h.remoteAddress should ===(remoteAkkaAddress)
          h.localAddress should ===(localAkkaAddress)
          h.handshakeInfo.uid should ===(33)

        case _ => fail()
      }

    }

    "handle explicit disassociate messages" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector,
          refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42))

      reader ! testAssociate(uid = 33)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle =>
          h.remoteAddress should ===(remoteAkkaAddress)
          h.localAddress should ===(localAkkaAddress)
          h

        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      reader ! testDisassociate(AssociationHandle.Unknown)

      expectMsg(Disassociated(AssociationHandle.Unknown))
    }

    "handle transport level disassociations" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector,
          refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42))

      reader ! testAssociate(uid = 33)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle =>
          h.remoteAddress should ===(remoteAkkaAddress)
          h.localAddress should ===(localAkkaAddress)
          h

        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      reader ! Disassociated(AssociationHandle.Unknown)

      expectMsg(Disassociated(AssociationHandle.Unknown))
    }

    "disassociate when failure detector signals failure" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val stateActor = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector,
          refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42))

      stateActor ! testAssociate(uid = 33)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle =>
          h.remoteAddress should ===(remoteAkkaAddress)
          h.localAddress should ===(localAkkaAddress)
          h

        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      //wait for one heartbeat
      awaitCond(lastActivityIsHeartbeat(registry))

      failureDetector.isAvailable = false

      expectMsg(Disassociated(AssociationHandle.Unknown))
    }

    "handle correctly when the handler is registered only after the association is already closed" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val stateActor = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf),
          codec,
          failureDetector,
          refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42))

      stateActor ! testAssociate(uid = 33)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle =>
          h.remoteAddress should ===(remoteAkkaAddress)
          h.localAddress should ===(localAkkaAddress)
          h

        case _ => fail()
      }

      stateActor ! Disassociated(AssociationHandle.Unknown)

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      expectMsg(Disassociated(AssociationHandle.Unknown))

    }

    "give up outbound after connection timeout" in {
      val (failureDetector, _, transport, handle) = collaborators
      handle.writable = false // nothing will be written
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val conf2 =
        ConfigFactory.parseString("akka.remote.classic.netty.tcp.connection-timeout = 500 ms").withFallback(conf)

      val stateActor = system.actorOf(
        ProtocolStateActor.outboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          remoteAddress,
          statusPromise,
          transport,
          new AkkaProtocolSettings(conf2),
          codec,
          failureDetector,
          refuseUid = None))

      watch(stateActor)
      intercept[TimeoutException] {
        Await.result(statusPromise.future, 5.seconds)
      }
      expectTerminated(stateActor)
    }

    "give up inbound after connection timeout" in {
      val (failureDetector, _, _, handle) = collaborators

      val conf2 =
        ConfigFactory.parseString("akka.remote.classic.netty.tcp.connection-timeout = 500 ms").withFallback(conf)

      val reader = system.actorOf(
        ProtocolStateActor.inboundProps(
          HandshakeInfo(origin = localAddress, uid = 42),
          handle,
          ActorAssociationEventListener(testActor),
          new AkkaProtocolSettings(conf2),
          codec,
          failureDetector))

      watch(reader)
      expectTerminated(reader)
    }

  }

}
