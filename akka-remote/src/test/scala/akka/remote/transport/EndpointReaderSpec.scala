package akka.remote.transport

import akka.actor.{ ExtendedActorSystem, Address, Props, ActorRef }
import akka.remote.transport.AkkaPduCodec.{ Associate, Heartbeat }
import akka.remote.transport.AssociationHandle.{ Disassociated, InboundPayload }
import akka.remote.transport.EndpointReaderSpec.{ TestMessageDispatcher, TestFailureDetector }
import akka.remote.transport.EndpointWriter.{ Retire, Ready }
import akka.remote.transport.TestTransport._
import akka.remote.{ RemoteProtocol, RemoteActorRefProvider, FailureDetector }
import akka.testkit.{ ImplicitSender, AkkaSpec }
import com.google.protobuf.{ ByteString ⇒ PByteString }
import com.typesafe.config.ConfigFactory
import scala.concurrent.util.duration._

object EndpointReaderSpec {

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
class EndpointReaderSpec extends AkkaSpec("""akka.actor.provider = "akka.remote.RemoteActorRefProvider" """) with ImplicitSender {

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

  def testHeartbeat = InboundPayload(codec.constructHeartbeat)
  def testPayload = InboundPayload(codec.constructMessagePdu(localAddress, self, testMsg, None))
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

  "EndpointReader" must {

    "register itself as reader on injecteted handles" in {
      val (failureDetector, msgDispatcher, _, _, handle) = collaborators

      system.actorOf(Props(new EndpointReader(
        false,
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector,
        msgDispatcher)))

      awaitCond(handle.readHandlerPromise.isCompleted)
    }

    "in passive (inbound) mode ignore incoming messages until Associate arrives but handle them after" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        false,
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector,
        msgDispatcher)))

      reader ! testHeartbeat
      reader ! testPayload

      expectNoMsg(100 milliseconds)

      failureDetector.called must be(false)
      msgDispatcher.called must be(false)

      reader ! testAssociate(None)

      expectMsg(Ready)

      failureDetector.called must be(true)
      msgDispatcher.called must be(false)

      // Heartbeat was sent in response to Associate
      awaitCond(lastActivityIsHeartbeat(registry))

      reader ! testPayload

      awaitCond(msgDispatcher.called)
    }

    "in active mode without WaitActivity send an associate immediately an signal readiness" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      expectMsg(Ready)

      lastActivityIsAssociate(registry, None) must be(true)
      failureDetector.called must be(true)

    }

    "in active mode with WaitActivity delay readiness until activity detected" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector,
        msgDispatcher)))

      awaitCond(lastActivityIsAssociate(registry, None))
      failureDetector.called must be(true)

      // keeps sending heartbeats
      awaitCond(lastActivityIsHeartbeat(registry))

      // finish connection by sending back a payload
      reader ! testPayload

      expectMsg(Ready)
      msgDispatcher.called must be(true)

    }

    "ignore incoming associations with wrong cookie" in {
      val (failureDetector, msgDispatcher, _, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        false,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.require-cookie = on").withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      reader ! testAssociate(None)
      reader ! testAssociate(Some("xyzzy"))

      expectNoMsg(100 milliseconds)

      reader ! testAssociate(Some("abcde"))

      expectMsg(Ready)
    }

    "send cookie in Associate if configured to do so" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString(
          """
            | akka.remoting.require-cookie = on
            | akka.remoting.wait-activity-enabled = off
          """.stripMargin).withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      expectMsg(Ready)

      lastActivityIsAssociate(registry, Some("abcde")) must be(true)
    }

    "handle explicit disassociate messages" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      expectMsg(Ready)

      lastActivityIsAssociate(registry, None) must be(true)

      reader ! testDisassociate

      expectMsg(Retire)
    }

    "handle transport level disassociations" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      expectMsg(Ready)

      lastActivityIsAssociate(registry, None) must be(true)

      reader ! Disassociated

      expectMsg(Retire)
    }

    "handle disassociate in WaitActivity period" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      val reader = system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(conf),
        codec,
        failureDetector,
        msgDispatcher)))

      awaitCond(lastActivityIsAssociate(registry, None))

      reader ! testDisassociate

      expectMsg(Retire)
    }

    "send Retire when failure detector signals failure" in {
      val (failureDetector, msgDispatcher, registry, _, handle) = collaborators

      system.actorOf(Props(new EndpointReader(
        true,
        handle,
        self,
        new RemotingConfig(ConfigFactory.parseString("akka.remoting.wait-activity-enabled = off").withFallback(conf)),
        codec,
        failureDetector,
        msgDispatcher)))

      expectMsg(Ready)

      lastActivityIsAssociate(registry, None) must be(true)

      //wait for one heartbeat
      awaitCond(lastActivityIsHeartbeat(registry))

      failureDetector.isAvailable = false

      expectMsg(Retire)
    }

    // Timeout from WaitActivity

  }

}
