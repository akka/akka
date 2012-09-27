package akka.remote.transport

import akka.actor.{ ExtendedActorSystem, Address, Props, ActorRef }
import akka.remote.transport.AkkaPduCodec.{Disassociate, Associate, Heartbeat}
import akka.remote.transport.AkkaProtocolSpec.{ TestMessageDispatcher, TestFailureDetector }
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }
import akka.remote.transport.EndpointWriter.{ Retire, Ready }
import akka.remote.transport.TestTransport._
import akka.remote.transport.Transport._
import akka.remote.{ RemoteProtocol, RemoteActorRefProvider, FailureDetector }
import akka.testkit.{ ImplicitSender, AkkaSpec }
import com.google.protobuf.{ ByteString ⇒ PByteString }
import com.typesafe.config.ConfigFactory
import scala.concurrent.{Await, Promise}
import scala.concurrent.util.duration._
import akka.util.ByteString

object AkkaProtocolSpec {

  class TestFailureDetector extends FailureDetector {
    @volatile var isAvailable: Boolean = true

    @volatile var called: Boolean = false

    def heartbeat(): Unit = called = true
  }

  class TestMessageDispatcher extends MessageDispatcher {
    @volatile var called: Boolean = false

    def dispatch(recipient: ActorRef, serializedMessage: Any, senderOption: Option[ActorRef]): Unit = called = true
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
      |  }
    """.stripMargin)

  val localAddress = Address("akka", "testsystem", "testhost", 1234)
  val remoteAddress = Address("akka", "testsystem2", "testhost2", 1234)

  val codec = AkkaPduProtobufCodec

  val provider = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]

  val testMsg = RemoteProtocol.MessageProtocol.newBuilder().setSerializerId(0).setMessage(PByteString.copyFromUtf8("foo")).build
  val testMsgPdu: ByteString = codec.constructMessagePdu(localAddress, self, testMsg, None)

  def testHeartbeat = InboundPayload(codec.constructHeartbeat)
  def testPayload = InboundPayload(testMsgPdu)

  def testDisassociate = InboundPayload(codec.constructDisassociate)
  def testAssociate(cookie: Option[String]) = InboundPayload(codec.constructAssociate(cookie, remoteAddress))

  def collaborators = {
    val registry = new AssociationRegistry
    val transport: TestTransport = new TestTransport(localAddress, registry)
    val handle: TestAssociationHandle = new TestAssociationHandle(localAddress, remoteAddress, transport, true)

    // silently drop writes -- we do not have another endpoint under test, nobody to forward to
    transport.writeBehavior.pushConstant(true)
    (new TestFailureDetector, new TestMessageDispatcher, registry, transport, handle)
  }

  //TODO: refactor this mess
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
        case _ ⇒ false
      }
    case _ ⇒ false
  }

  //TODO: two groups, inbound and outbound
  //TODO: test origin handling
  "InboundStateActor" must {

    "register itself as reader on injecteted handles" in {
      val (failureDetector, msgDispatcher, _, _, handle) = collaborators

      system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector
      )))

      awaitCond(handle.readHandlerPromise.isCompleted)
    }

    //TODO: test the same for outbound

    "in passive (inbound) mode accept payload after Associate PDU received" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector)))

      reader ! testAssociate(None)

      awaitCond(failureDetector.called)

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h) => h
      }

      wrappedHandle.readHandlerPromise.success(self)

      failureDetector.called must be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))

      reader ! testPayload

      expectMsgPF() {
        case InboundPayload(p) if p == testMsgPdu =>
      }
    }

    "in passive mode disassociate when an unexpected message arrives instead of Associate" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector)))

      // a stray message will force a disassociate
      reader ! testHeartbeat

      // this associate will now be ignored
      reader ! testAssociate(None)

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) => true
        case _ => false
      })
    }

    "an OutboundStateActor without WaitActivity send an associate immediately an signal readiness" in {
      val (failureDetector, _, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector
      )))

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(handle) if handle.remoteAddress == remoteAddress && handle.localAddress == localAddress =>
        case _ => fail()
      }

      lastActivityIsAssociate(registry, None) must be(true)
      failureDetector.called must be(true)

    }

    "in active mode with WaitActivity delay readiness until activity detected" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(conf),
        codec,
        failureDetector
      )))

      awaitCond(lastActivityIsAssociate(registry, None))
      failureDetector.called must be(true)

      // keeps sending heartbeats
      awaitCond(lastActivityIsHeartbeat(registry))

      statusPromise.isCompleted must be(false)

      // finish connection by sending back a payload
      reader ! testPayload

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(handle) if handle.remoteAddress == remoteAddress && handle.localAddress == localAddress =>
        case _ => fail()
      }

      // TODO: test for the incoming payload as well

    }

    "ignore incoming associations with wrong cookie" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector)))

      reader ! testAssociate(Some("xyzzy"))

      awaitCond(registry.logSnapshot.exists {
        case DisassociateAttempt(requester, remote) => true
        case _ => false
      })
    }

    "accept incoming associations with correct cookie" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new ProtocolStateActor(
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector)))

      // Send the correct cookie
      reader ! testAssociate(Some("abcde"))

      val wrappedHandle = expectMsgPF() {
        case InboundAssociation(h) => h
      }

      wrappedHandle.readHandlerPromise.success(self)

      failureDetector.called must be(true)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))
    }

    "send cookie in Associate if configured to do so" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(ConfigFactory.parseString(
          """
            | akka.remoting.require-cookie = on
            | akka.remoting.wait-activity-enabled = off
          """.stripMargin).withFallback(conf)),
        codec,
        failureDetector
      )))

      Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(handle) if handle.remoteAddress == remoteAddress && handle.localAddress == localAddress =>
        case _ => fail()
      }

      lastActivityIsAssociate(registry, Some("abcde")) must be(true)
    }

    "handle explicit disassociate messages" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector
      )))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) if h.remoteAddress == remoteAddress && h.localAddress == localAddress => h
        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      lastActivityIsAssociate(registry, None) must be(true)

      reader ! testDisassociate

      expectMsg(Disassociated)
    }

    "handle transport level disassociations" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val reader = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(conf),
        codec,
        failureDetector
      )))

      awaitCond(lastActivityIsAssociate(registry, None))

      // Finish association with a heartbeat -- pushes state out of WaitActivity
      reader ! testHeartbeat

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) if h.remoteAddress == remoteAddress && h.localAddress == localAddress => h
        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      Thread.sleep(100)

      reader ! Disassociated

      expectMsg(Disassociated)
    }

    "send Retire when failure detector signals failure" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector
      )))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) if h.remoteAddress == remoteAddress && h.localAddress == localAddress => h
        case _ => fail()
      }

      wrappedHandle.readHandlerPromise.success(self)

      lastActivityIsAssociate(registry, None) must be(true)

      //wait for one heartbeat
      awaitCond(lastActivityIsHeartbeat(registry))

      failureDetector.isAvailable = false

      expectMsg(Disassociated)
    }

    "handle correctly when the handler is registered after the association is already closed" in {
      val (failureDetector, msgDispatcher, registry, transport, handle) = collaborators
      transport.associateBehavior.pushConstant(Transport.Ready(handle))

      val statusPromise: Promise[Status] = Promise()

      val stateActor = system.actorOf(Props(new ProtocolStateActor(
        remoteAddress,
        statusPromise,
        transport,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector
      )))

      val wrappedHandle = Await.result(statusPromise.future, 3 seconds) match {
        case Transport.Ready(h) if h.remoteAddress == remoteAddress && h.localAddress == localAddress => h
        case _ => fail()
      }

      stateActor ! Disassociated

      wrappedHandle.readHandlerPromise.success(self)

      expectMsg(Disassociated)

    }

  }

}
