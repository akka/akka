package akka.remote.actmote

import akka.testkit._
import akka.actor._
import scala.Some
import akka.remote._
import actmote.TransportConnector._
import com.typesafe.config.{ ConfigFactory, Config }
import scala.Some
import java.util.concurrent.CopyOnWriteArrayList
import akka.actor.SupervisorStrategy.{ Stop, Restart }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class EndpointActorSpec extends AkkaSpec with ImplicitSender with DefaultTimeout {
  val localAddress = Address("test", "testsystem", "testocalhost", 7357)
  val remoteAddress = Address("test", "testsystem", "testremotehost", 7357)

  val extendedSystem = system.asInstanceOf[ExtendedActorSystem]

  val remoteSettings = new RemoteSettings(ConfigFactory.parseString(
    """
      |  akka.remote {
      |    transport = "akka.remote.actmote.ActorManagedRemoting"
      |    log-received-messages = on
      |    log-sent-messages = on
      |    remote-daemon-ack-timeout = 5 s
      |    untrusted-mode = false
      |    log-remote-lifecycle-events = on
      |  }
    """.stripMargin), "testsystem")
  val managedRemoteSettings = new ActorManagedRemotingSettings(ConfigFactory.parseString(
    """
      |  akka.remote.managed {
      |    connector = "akka.remote.actmote.DummyTransportConnector"
      |    use-passive-connections = true
      |    startup-timeout = 5 s
      |    shutdown-timeout = 5 s
      |    preconnect-buffer-size = 1000
      |    retry-latch-closed-for = 0
      |    flow-control-backoff = 50 ms
      |  }
    """.stripMargin))

  def mockLifeCycleNotifier = new LifeCycleNotifier {
    def remoteClientConnected(remoteAddress: Address) {}
    def remoteClientShutdown(remoteAddress: Address) {}
    def remoteServerClientConnected(remoteAddress: Address) {}
    def remoteServerClientDisconnected(remoteAddress: Address) {}
    def remoteClientStarted(remoteAddress: Address) {}
    def remoteClientError(reason: Throwable, remoteAddress: Address) {}
    def remoteClientDisconnected(remoteAddress: Address) {}
    def remoteServerError(reason: Throwable) {}
    def remoteServerShutdown() {}
    def remoteServerStarted() {}
    def remoteServerClientClosed(remoteAddress: Address) {}
  }

  def mockConnector = new TransportConnector(null, null) {
    @volatile var connectingTo: Address = _
    @volatile var connectingWith: ActorRef = _

    def listen(responsibleActor: ActorRef) {}
    def connect(remote: Address, responsibleActorForConnection: ActorRef) { connectingTo = remote; connectingWith = responsibleActorForConnection }
    def shutdown() {}
  }

  def mockHandle = new TransportConnectorHandle(null) {
    def remoteAddress = EndpointActorSpec.this.remoteAddress
    def localAddress = EndpointActorSpec.this.localAddress

    @volatile var openedWith: ActorRef = _
    @volatile var closed = false

    // This is the slowest, but it is snapshot consistent
    val writeLog = new CopyOnWriteArrayList[Send]()

    def open(responsibleActor: ActorRef) { openedWith = responsibleActor }
    // TODO: test backoff behavior
    def write(msg: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) = { writeLog.add(Send(msg, senderOption, recipient)); true }
    def close() { closed = true }
  }

  def createEndpointActor(notifier: LifeCycleNotifier, connector: TransportConnector, handleOption: Option[TransportConnectorHandle]) = system.actorOf(Props(new EndpointActor(
    notifier,
    connector,
    remoteSettings,
    managedRemoteSettings,
    localAddress,
    remoteAddress,
    handleOption)).withDispatcher("akka.actor.default-stash-dispatcher"))

  "EnpointActor" must {

    "open handle after startup if one is injected" in {
      val handle = mockHandle
      val endpointActor = createEndpointActor(mockLifeCycleNotifier, mockConnector, Some(handle))
      awaitCond(handle.openedWith == endpointActor)
    }

    "initiate connection after startup if no handle is given" in {
      val connector = mockConnector
      val endpointActor = createEndpointActor(mockLifeCycleNotifier, connector, None)
      awaitCond(connector.connectingTo == remoteAddress)
      assert(connector.connectingWith == endpointActor)
    }

    "open handle after connection finished" in {
      val connector = mockConnector
      val handle = mockHandle
      val endpointActor = createEndpointActor(mockLifeCycleNotifier, connector, None)
      awaitCond(connector.connectingTo == remoteAddress)
      endpointActor ! ConnectionInitialized(handle)
      awaitCond(handle.openedWith == endpointActor)
    }

    "stop on receiving Disconnect, and close handle after stop" in {
      val handle = mockHandle
      assert(handle.closed == false)

      val endpointActor = createEndpointActor(mockLifeCycleNotifier, mockConnector, None)
      watch(endpointActor)
      endpointActor ! ConnectionInitialized(handle)
      endpointActor ! Disconnected(handle)
      expectMsgPF() {
        case Terminated(_) ⇒ true
      }

      assert(handle.closed == true)
    }

    "buffer sends before connection and send them after successfully connected" in {
      val handle = mockHandle
      val endpointActor = createEndpointActor(mockLifeCycleNotifier, mockConnector, None)

      val toSendPartOne = (1 to 10) map { i ⇒ Send(i, Some(self), null) } toList
      val toSendPartTwo = (11 to 20) map { i ⇒ Send(i, Some(self), null) } toList
      val allSends = toSendPartOne ++ toSendPartTwo

      toSendPartOne foreach { endpointActor ! _ }
      endpointActor ! ConnectionInitialized(handle)
      toSendPartTwo foreach { endpointActor ! _ }

      awaitCond(handle.writeLog.size() == 20)

      val sendsEqual = allSends.zipWithIndex.forall { case (send, i) ⇒ send == handle.writeLog.get(i) }
      assert(sendsEqual)
    }

    "if restarted on failure: reconnect to remote, and send messages without loss" in {
      val connector = mockConnector
      val handleOne = mockHandle
      @volatile var endpointActor: ActorRef = null

      // 1. start endpoint with injected handle
      val mediator = system.actorOf(Props(new Actor {
        override val supervisorStrategy = OneForOneStrategy() {
          case _: EndpointConnectorProtocolViolated ⇒ Stop
          case _: EndpointException                 ⇒ Restart
        }
        def receive = { case _ ⇒ () }

        override def preStart() {
          // Ugly hack using external closed over mutable state.
          endpointActor = createEndpointActor(mockLifeCycleNotifier, connector, Some(handleOne))
        }
      }), "testsupervisor")
      awaitCond(endpointActor != null)

      // 2. start sending messages
      val toSendPartOne = (1 to 10) map { i ⇒ Send(i, Some(self), null) } toList;
      toSendPartOne foreach { endpointActor ! _ }

      // 3. wait for handle open
      awaitCond(handleOne.openedWith == endpointActor)
      assert(connector.connectingTo == null) // Connecting was not initiated at this point

      // 4. send second bunch of messages
      val toSendPartTwo = (11 to 20) map { i ⇒ Send(i, Some(self), null) } toList;
      toSendPartTwo foreach { endpointActor ! _ }

      // 5. inject failure
      endpointActor ! ConnectionFailed(new IllegalStateException("Test exception"))

      // 6. send third bunch of messages
      val toSendPartThree = (21 to 30) map { i ⇒ Send(i, Some(self), null) } toList;
      toSendPartThree foreach { endpointActor ! _ }

      // 7. assert that a connection was requested (reconnect)
      awaitCond(connector.connectingTo == remoteAddress && connector.connectingWith == endpointActor)

      // 8. send last bunch of messages
      val toSendPartFour = (31 to 40) map { i ⇒ Send(i, Some(self), null) } toList;
      toSendPartFour foreach { endpointActor ! _ }

      // 8. finish connection (inject new handle)
      val handleTwo = mockHandle
      endpointActor ! ConnectionInitialized(handleTwo)

      // 7. assert handle was open
      awaitCond(handleTwo.openedWith == endpointActor)

      // 9. wait until writes on first and second handle reach the total number of sends
      awaitCond(handleOne.writeLog.size() + handleTwo.writeLog.size() == 40)

      // 10. check that all messages has been written to the two handle and sent in the right order
      val allSends = toSendPartOne ++ toSendPartTwo ++ toSendPartThree ++ toSendPartFour
      val mergedSendLog = new java.util.ArrayList[Send]()
      mergedSendLog.addAll(handleOne.writeLog)
      mergedSendLog.addAll(handleTwo.writeLog)
      val sendsEqual = allSends.zipWithIndex.forall { case (send, i) ⇒ send == mergedSendLog.get(i) }

      assert(sendsEqual)
    }

  }

}
