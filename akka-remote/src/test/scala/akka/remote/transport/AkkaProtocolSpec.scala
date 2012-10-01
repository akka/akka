package akka.remote.transport

import akka.actor.{ ExtendedActorSystem, Address, Props }
import akka.remote.transport.AkkaPduCodec.{ Disassociate, Associate, Heartbeat }
import akka.remote.transport.AkkaProtocolSpec.TestFailureDetector
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport._
import akka.remote.{ RemoteProtocol, RemoteActorRefProvider, FailureDetector }
import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.util.ByteString
import com.google.protobuf.{ ByteString ⇒ PByteString }
import com.typesafe.config.ConfigFactory
import scala.concurrent.util.duration._
import scala.concurrent.{ Await, Promise }

object AkkaProtocolSpec {

  class TestFailureDetector extends FailureDetector {
    @volatile var isAvailable: Boolean = true

    @volatile var called: Boolean = false

    def heartbeat(): Unit = called = true
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AkkaProtocolSpec extends AkkaSpec("""akka.actor.provider = "akka.remote.RemoteActorRefProvider" """) with ImplicitSender {

  //TODO: merge with constructor parameter
  val conf = ConfigFactory.parseString(
    """
      |  akka.remoting {
      |
      |    failure-detector {
      |      threshold = 7.0
      |      max-sample-size = 100
      |      min-std-deviation = 100 ms
      |      acceptable-heartbeat-pause = 3 s
      |    }
      |
      |    heartbeat-interval = 0.1 s
      |
      |    wait-activity-enabled = on
      |
      |    backoff-interval = 1 s
      |
      |    require-cookie = off
      |
      |    secure-cookie = "abcde"
      |
      |    shutdown-timeout = 5 s
      |
      |    startup-timeout = 5 s
      |
      |    retry-latch-closed-for = 0 s
      |
      |    use-passive-connections = on
      |  }
    """.stripMargin)

  val localAddress = Address("test", "testsystem", "testhost", 1234)
  val localAkkaAddress = Address("test.akka", "testsystem", "testhost", 1234)

  val remoteAddress = Address("test", "testsystem2", "testhost2", 1234)
  val remoteAkkaAddress = Address("test.akka", "testsystem2", "testhost2", 1234)

  val codec = AkkaPduProtobufCodec

  val provider = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]

  val testMsg = RemoteProtocol.MessageProtocol.newBuilder().setSerializerId(0).setMessage(PByteString.copyFromUtf8("foo")).build
  val testMsgPdu: ByteString = codec.constructMessagePdu(localAkkaAddress, self, testMsg, None)

  def testHeartbeat = InboundPayload(codec.constructHeartbeat)
  def testPayload = InboundPayload(testMsgPdu)

  def testDisassociate = InboundPayload(codec.constructDisassociate)
  def testAssociate(cookie: Option[String]) = InboundPayload(codec.constructAssociate(cookie, remoteAkkaAddress))

  def collaborators = {
    val registry = new AssociationRegistry
    val transport: TestTransport = new TestTransport(localAddress, registry)
    val handle: TestAssociationHandle = new TestAssociationHandle(localAddress, remoteAddress, transport, true)

    // silently drop writes -- we do not have another endpoint under test, nobody to forward to
    transport.writeBehavior.pushConstant(true)
    (new TestFailureDetector, registry, transport, handle)
  }

  def lastActivityIsHeartbeat(registry: AssociationRegistry) = if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
    case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
      codec.decodePdu(payload, provider) match {
        case Heartbeat ⇒ true
        case _         ⇒ false
      }
    case _ ⇒ false
  }

  def lastActivityIsAssociate(registry: AssociationRegistry, cookie: Option[String]) = if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
    case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
      codec.decodePdu(payload, provider) match {
        case Associate(c, origin) if c == cookie && origin == localAddress ⇒ true
        case _ ⇒ false
      }
    case _ ⇒ false
  }

  def lastActivityIsDisassociate(registry: AssociationRegistry) = if (registry.logSnapshot.isEmpty) false else registry.logSnapshot.last match {
    case WriteAttempt(sender, recipient, payload) if sender == localAddress && recipient == remoteAddress ⇒
      codec.decodePdu(payload, provider) match {
        case Disassociate ⇒ true
        case _            ⇒ false
      }
    case _ ⇒ false
  }

  //TODO: test origin handling
  "ProtocolStateActor" must {

    "register itself as reader on injecteted handles" in {
      val (failureDetector, _, _, handle) = collaborators

      system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new AkkaProtocolConfig(conf),
        codec,
        failureDetector)))

      awaitCond(handle.readHandlerPromise.isCompleted)
    }

    "in inbound mode accept payload after Associate PDU received" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new AkkaProtocolConfig(conf),
        codec,
        failureDetector)))

      reader ! testAssociate(None)

      awaitCond(failureDetector.called)

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h) ⇒ h
      }

      wrappedHandle.readHandlerPromise.success(self)

      failureDetector.called must be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))

      reader ! testPayload

      expectMsgPF() {
        case InboundPayload(p) ⇒ p must be === testMsgPdu
      }
    }

    "in inbound mode disassociate when an unexpected message arrives instead of Associate" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new AkkaProtocolConfig(conf),
        codec,
        failureDetector)))

      // a stray message will force a disassociate
      reader ! testHeartbeat

      // this associate will now be ignored
      reader ! testAssociate(None)

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) ⇒ true
        case _                                      ⇒ false
      })
    }

    "serve the handle as soon as possible if WaitActivity is turned off" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector)))

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress

        case _ ⇒ fail()
      }

      lastActivityIsAssociate(registry, None) must be(true)
      failureDetector.called must be(true)

    }

    "in outbound mode with WaitActivity delay readiness until activity detected" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(conf),
        codec,
        failureDetector)))

      awaitCond(lastActivityIsAssociate(registry, None))
      failureDetector.called must be(true)

      // keeps sending heartbeats
      awaitCond(lastActivityIsHeartbeat(registry))

      statusPromise.isCompleted must be(false)

      // finish connection by sending back a payload
      reader ! testPayload

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress

        case _ ⇒ fail()
      }

    }

    "ignore incoming associations with wrong cookie" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector)))

      reader ! testAssociate(Some("xyzzy"))

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) ⇒ true
        case _                                      ⇒ false
      })
    }

    "accept incoming associations with correct cookie" in {
      val (failureDetector, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector)))

      // Send the correct cookie
      reader ! testAssociate(Some("abcde"))

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h) ⇒ h
      }

      wrappedHandle.readHandlerPromise.success(self)

      failureDetector.called must be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))
    }

    "send cookie in Associate PDU if configured to do so" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(ConfigFactory.parseString(
          """
            | akka.remoting.require-cookie = on
            | akka.remoting.wait-activity-enabled = off
          """.stripMargin).withFallback(conf)),
        codec,
        failureDetector)))

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress

        case _ ⇒ fail()
      }

      lastActivityIsAssociate(registry, Some("abcde")) must be(true)
    }

    "handle explicit disassociate messages" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector)))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress
          h

        case _ ⇒ fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      lastActivityIsAssociate(registry, None) must be(true)

      reader ! testDisassociate

      expectMsg(Disassociated)
    }

    "handle transport level disassociations" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(conf),
        codec,
        failureDetector)))

      awaitCond(lastActivityIsAssociate(registry, None))

      // Finish association with a heartbeat -- pushes state out of WaitActivity
      reader ! testHeartbeat

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress
          h

        case _ ⇒ fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      Thread.sleep(100)

      reader ! Disassociated

      expectMsg(Disassociated)
    }

    "disassociate when failure detector signals failure" in {
      val (failureDetector, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector)))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress
          h

        case _ ⇒ fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      lastActivityIsAssociate(registry, None) must be(true)

      //wait for one heartbeat
      awaitCond(lastActivityIsHeartbeat(registry))

      failureDetector.isAvailable = false

      expectMsg(Disassociated)
    }

    "handle correctly when the handler is registered only after the association is already closed" in {
      val (failureDetector, _, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val stateActor = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new AkkaProtocolConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector)))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) ⇒
          h.remoteAddress must be === remoteAkkaAddress
          h.localAddress must be === localAkkaAddress
          h

        case _ ⇒ fail()
      }

      stateActor ! Disassociated

      wrappedHandle.readHandlerPromise.success(self)

      expectMsg(Disassociated)

    }

    // TODO: test maximum frame violation handling

  }

}
