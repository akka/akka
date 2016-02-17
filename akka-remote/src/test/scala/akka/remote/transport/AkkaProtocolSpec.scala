package akka.remote.transport

import akka.actor.{ ExtendedActorSystem, Address, Props }
import akka.remote.transport.AkkaPduCodec.{ Disassociate, Associate, Heartbeat }
import akka.remote.transport.AkkaProtocolSpec.TestFailureDetector
import akka.remote.transport.AssociationHandle.{ DisassociateInfo, ActorHandleEventListener, Disassociated, InboundPayload }
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport._
import akka.remote.{ SeqNo, WireFormats, RemoteActorRefProvider, FailureDetector }
import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.util.ByteString
import com.google.protobuf.{ ByteString ⇒ PByteString }
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
import akka.actor.Deploy
import java.util.concurrent.TimeoutException

object AkkaProtocolSpec {

  class TestFailureDetector extends FailureDetector {
    @volatile var isAvailable: Boolean = true

    def isMonitoring: Boolean = called

    @volatile var called: Boolean = false

    def heartbeat(): Unit = called = true
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaProtocolSpec extends AkkaSpec("""akka.actor.provider = "akka.remote.RemoteActorRefProvider" """) with ImplicitSender {

  val conf = ConfigFactory.parseString(
    """
      akka.remote {

        transport-failure-detector {
          implementation-class = "akka.remote.PhiAccrualFailureDetector"
          threshold = 7.0
          max-sample-size = 100
          min-std-deviation = 100 ms
          acceptable-heartbeat-pause = 3 s
          heartbeat-interval = 1 s
        }

        backoff-interval = 1 s

        require-cookie = off

        secure-cookie = "abcde"

        shutdown-timeout = 5 s

        startup-timeout = 5 s

        use-passive-connections = on
      }
  """).withFallback(system.settings.config)

  val localAddress = Address("test", "testsystem", "testhost", 1234)
  val localAkkaAddress = Address("akka.test", "testsystem", "testhost", 1234)

  val remoteAddress = Address("test", "testsystem2", "testhost2", 1234)
  val remoteAkkaAddress = Address("akka.test", "testsystem2", "testhost2", 1234)

  val codec = AkkaPduProtobufCodec

  val testMsg = WireFormats.SerializedMessage.newBuilder().setSerializerId(0).setMessage(PByteString.copyFromUtf8("foo")).build
  val testEnvelope = codec.constructMessage(localAkkaAddress, testActor, testMsg, None)
  val testMsgPdu: ByteString = codec.constructPayload(testEnvelope)

  def testHeartbeat = InboundPayload(codec.constructHeartbeat)
  def testPayload = InboundPayload(testMsgPdu)

  def testDisassociate(info: DisassociateInfo) = InboundPayload(codec.constructDisassociate(info))
  def testAssociate(uid: Int, cookie: Option[String]) =
    InboundPayload(codec.constructAssociate(HandshakeInfo(remoteAkkaAddress, uid, cookie)))

  def collaborators = {
    val registry = new AssociationRegistry
    val transport: TestTransport = new TestTransport(localAddress, registry)
    val handle: TestAssociationHandle = new TestAssociationHandle(localAddress, remoteAddress, transport, true)

    // silently drop writes -- we do not have another endpoint under test, so nobody to forward to
    transport.writeBehavior.pushConstant(true)
    (new TestFailureDetector, registry, transport, handle)
  }

  def lastActivityIsHeartbeat(registry: AssociationRegistry) =
    if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
      case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
        codec.decodePdu(payload) match {
          case Heartbeat ⇒ true
          case _         ⇒ false
        }
      case _ ⇒ false
    }

  def lastActivityIsAssociate(registry: AssociationRegistry, uid: Long, cookie: Option[String]) =
    if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
      case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
        codec.decodePdu(payload) match {
          case Associate(info) ⇒
            info.cookie == cookie && info.origin == localAddress && info.uid == uid
          case _ ⇒ false
        }
      case _ ⇒ false
    }

  def lastActivityIsDisassociate(registry: AssociationRegistry) =
    if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
      case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
        codec.decodePdu(payload) match {
          case Disassociate(_) ⇒ true
          case _               ⇒ false
        }
      case _ ⇒ false
    }

  "ProtocolStateActor" must {

    "register itself as reader on injecteted handles" in {
      val (failureDetector, _, _, handle) = collaborators

      system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        handle,
        ActorAssociationEventListener(testActor),
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector))

      awaitCond(handle.readHandlerPromise.isCompleted)
    }

    "in inbound mode accept payload after Associate PDU received" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        handle,
        ActorAssociationEventListener(testActor),
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector))

      reader ! testAssociate(uid = 33, cookie = None)

      awaitCond(failureDetector.called)

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h: AkkaProtocolHandle) ⇒
          h.handshakeInfo.uid should be(33)
          h
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      failureDetector.called should be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))

      reader ! testPayload

      expectMsgPF() {
        case InboundPayload(p) ⇒ p should be(testEnvelope)
      }
    }

    "in inbound mode disassociate when an unexpected message arrives instead of Associate" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        handle,
        ActorAssociationEventListener(testActor),
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector))

      // a stray message will force a disassociate
      reader ! testHeartbeat

      // this associate will now be ignored
      reader ! testAssociate(uid = 33, cookie = None)

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) ⇒ true
        case _                                      ⇒ false
      })
    }

    "in outbound mode delay readiness until hadnshake finished" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, 42, None))
      failureDetector.called should be(true)

      // keeps sending heartbeats
      awaitCond(lastActivityIsHeartbeat(registry))

      statusPromise.isCompleted should be(false)

      // finish connection by sending back an associate message
      reader ! testAssociate(33, None)

      Await.result(statusPromise.future, 3.seconds) match {
        case h: AkkaProtocolHandle ⇒
          h.remoteAddress should be(remoteAkkaAddress)
          h.localAddress should be(localAkkaAddress)
          h.handshakeInfo.uid should be(33)

        case _ ⇒ fail()
      }

    }

    "ignore incoming associations with wrong cookie" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = Some("abcde")),
        handle,
        ActorAssociationEventListener(testActor),
        new AkkaProtocolSettings(ConfigFactory.parseString("akka.remote.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector))

      reader ! testAssociate(uid = 33, Some("xyzzy"))

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) ⇒ true
        case _                                      ⇒ false
      })
    }

    "accept incoming associations with correct cookie" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = Some("abcde")),
        handle,
        ActorAssociationEventListener(testActor),
        new AkkaProtocolSettings(ConfigFactory.parseString("akka.remote.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector))

      // Send the correct cookie
      reader ! testAssociate(uid = 33, Some("abcde"))

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h: AkkaProtocolHandle) ⇒
          h.handshakeInfo.uid should be(33)
          h.handshakeInfo.cookie should be(Some("abcde"))
          h
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      failureDetector.called should be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))
    }

    "send cookie in Associate PDU if configured to do so" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = Some("abcde")),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(ConfigFactory.parseString("akka.remote.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42, cookie = Some("abcde")))
    }

    "handle explicit disassociate messages" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42, cookie = None))

      reader ! testAssociate(uid = 33, cookie = None)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle ⇒
          h.remoteAddress should be(remoteAkkaAddress)
          h.localAddress should be(localAkkaAddress)
          h

        case _ ⇒ fail()
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      reader ! testDisassociate(AssociationHandle.Unknown)

      expectMsg(Disassociated(AssociationHandle.Unknown))
    }

    "handle transport level disassociations" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val reader = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42, cookie = None))

      reader ! testAssociate(uid = 33, cookie = None)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle ⇒
          h.remoteAddress should be(remoteAkkaAddress)
          h.localAddress should be(localAkkaAddress)
          h

        case _ ⇒ fail()
      }

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      reader ! Disassociated(AssociationHandle.Unknown)

      expectMsg(Disassociated(AssociationHandle.Unknown))
    }

    "disassociate when failure detector signals failure" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val stateActor = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42, cookie = None))

      stateActor ! testAssociate(uid = 33, cookie = None)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle ⇒
          h.remoteAddress should be(remoteAkkaAddress)
          h.localAddress should be(localAkkaAddress)
          h

        case _ ⇒ fail()
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

      val stateActor = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolSettings(conf),
        codec,
        failureDetector,
        refuseUid = None))

      awaitCond(lastActivityIsAssociate(registry, uid = 42, cookie = None))

      stateActor ! testAssociate(uid = 33, cookie = None)

      val wrappedHandle = Await.result(statusPromise.future, 3.seconds) match {
        case h: AssociationHandle ⇒
          h.remoteAddress should be(remoteAkkaAddress)
          h.localAddress should be(localAkkaAddress)
          h

        case _ ⇒ fail()
      }

      stateActor ! Disassociated(AssociationHandle.Unknown)

      wrappedHandle.readHandlerPromise.success(ActorHandleEventListener(testActor))

      expectMsg(Disassociated(AssociationHandle.Unknown))

    }

    "give up outbound after connection timeout" in {
      val (failureDetector, registry, transport, handle) = collaborators
      handle.writable = false // nothing will be written
      transport.associateBehavior.pushConstant(handle)

      val statusPromise: Promise[AssociationHandle] = Promise()

      val conf2 = ConfigFactory.parseString("akka.remote.netty.tcp.connection-timeout = 500 ms").
        withFallback(conf)

      val stateActor = system.actorOf(ProtocolStateActor.outboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
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
      val (failureDetector, registry, _, handle) = collaborators

      val conf2 = ConfigFactory.parseString("akka.remote.netty.tcp.connection-timeout = 500 ms").
        withFallback(conf)

      val reader = system.actorOf(ProtocolStateActor.inboundProps(
        HandshakeInfo(origin = localAddress, uid = 42, cookie = None),
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
