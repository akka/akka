package akka.remote.actmote

import akka.actor._
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.pattern.gracefulStop
import akka.remote._
import actmote.TransportConnector._
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.util.duration._
import util.control.NonFatal
import concurrent.util.Deadline

class ActorManagedRemoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  val HeadActorName = "remoteTransportHeadActor"
  val managedRemoteSettings = new ActorManagedRemotingSettings(provider.remoteSettings.config.getConfig("akka.remote.managed"))

  @volatile var headActor: ActorRef = _
  @volatile var address: Address = _
  @volatile var transport: TransportConnector = _

  def loadTransport = {
    val fqn = managedRemoteSettings.Connector
    val args = Seq(
      classOf[ExtendedActorSystem] -> system,
      classOf[RemoteActorRefProvider] -> provider)

    system.dynamicAccess.createInstanceFor[TransportConnector](fqn, args) match {
      case Left(problem)    ⇒ println(problem); throw new RemoteTransportException("Could not load transport connector " + fqn, problem)
      case Right(connector) ⇒ connector
    }
  }

  lazy val log: LoggingAdapter = Logging(system.eventStream, "ActorManagedRemoting(" + address + ")")

  def shutdown() {
    if (headActor != null) {
      try {
        val stopped: Future[Boolean] = gracefulStop(headActor, 5 seconds)(system)
        // the actor has been stopped
        if (Await.result(stopped, managedRemoteSettings.ShutdownTimeout)) {
          headActor = null
          transport = null
          log.info("Remoting stopped successfully")
        }

      } catch {
        case e: akka.pattern.AskTimeoutException ⇒ log.warning("Shutdown timed out")
        case NonFatal(e) => log.error(e, "Shutdown failed")
      }
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  def start() {
    log.info("Starting remoting")
    if (headActor eq null) {
      transport = loadTransport
      headActor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new HeadActor(provider, transport, managedRemoteSettings)), HeadActorName)

      val timeout = new Timeout(managedRemoteSettings.StartupTimeout)
      val addressFuture = headActor.ask(Listen(this))(timeout).mapTo[Address]

      try {
        this.address = Await.result(addressFuture, timeout.duration)
      } catch {
        case e: akka.pattern.AskTimeoutException => throw new RemoteTransportException("Startup timed out", e)
        case NonFatal(e) => throw new RemoteTransportException("Startup failed", e)
      }
    }
  }

  // TODO: this is called in RemoteActorRefProvider to handle the lifecycle of connections (clients)
  // Originally calls back to RemoteTransport, but now this should be handled as the lifecycle of actors
  def shutdownClientConnection(address: Address) {
    // Ignore
  }

  // TODO: this is never called anywhere, should be taken out from RemoteTransport API
  def restartClientConnection(address: Address) {
    // Ignore
  }

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    headActor ! Send(message, senderOption, recipient)
  }

  protected def useUntrustedMode = provider.remoteSettings.UntrustedMode

  protected def logRemoteLifeCycleEvents = provider.remoteSettings.LogRemoteLifeCycleEvents

}

// Cut out
trait LifeCycleNotificationHelper {
  def provider: RemoteActorRefProvider
  def extendedSystem: ExtendedActorSystem
  def log: LoggingAdapter

  def useUntrustedMode = provider.remoteSettings.UntrustedMode
  def logRemoteLifeCycleEvents = provider.remoteSettings.LogRemoteLifeCycleEvents

  def notifyListeners(message: RemoteLifeCycleEvent): Unit = {
    extendedSystem.eventStream.publish(message)
    if (logRemoteLifeCycleEvents) log.log(message.logLevel, "{}", message)
  }
}

private[actmote] sealed trait RemotingCommand
private[actmote] case class Listen(transport: RemoteTransport)
// No longer needed, if shutdownClient and restartClient are removed from RemoteTransport API
//private[actmote] case class ShutdownEndpoint(address: Address) extends RemotingCommand
//private[actmote] case class RestartEndpoint(address: Address) extends RemotingCommand
private[actmote] case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand

object HeadActor {
  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Failed(timeOfFailure: Deadline) extends EndpointPolicy

  // TODO: How to handle passive connections?
  class EndpointRegistry {
    private val addressToEndpointAndPolicy = scala.collection.mutable.Map[Address, EndpointPolicy]()
    private val endpointToAddress = scala.collection.mutable.Map[ActorRef, Address]()

    def getEndpointWithPolicy(address: Address) = addressToEndpointAndPolicy.get(address)

    def registerEndpoint(address: Address, endpoint: ActorRef) {
      addressToEndpointAndPolicy += address -> Pass(endpoint)
      endpointToAddress += endpoint -> address
    }

    def markFailed(endpoint: ActorRef, timeOfFailure: Deadline) {
      val address = endpointToAddress(endpoint)
      endpointToAddress.remove(endpoint)
      addressToEndpointAndPolicy(address) = Failed(timeOfFailure)
    }

    def markPass(endpoint: ActorRef) {
      val address = endpointToAddress(endpoint)
      addressToEndpointAndPolicy(address) = Pass(endpoint)
    }

    def remove(endpoint: ActorRef) {
      val address = endpointToAddress(endpoint)
      endpointToAddress.remove(endpoint)
      addressToEndpointAndPolicy.remove(address)
    }
  }
}

// TODO: HeadActor MUST WATCH his endpoint Actors
class HeadActor(
  val provider: RemoteActorRefProvider,
  val connector: TransportConnector,
  val settings: ActorManagedRemotingSettings) extends Actor with LifeCycleNotificationHelper {

  import akka.actor.SupervisorStrategy._
  import actmote.TransportConnector.IncomingConnection
  import HeadActor._

  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val log = Logging(context.system.eventStream, "HeadActor")

  private var address: Address = _
  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry()

  private var transport: RemoteTransport = _
  private var startupFuture: ActorRef = _

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: EndpointException ⇒ Stop
    case NonFatal(_) => Stop // What exceptions should we escalate?
  }

  def receive = {
    case Listen(transport) ⇒ {
      this.transport = transport
      startupFuture = sender
      connector.listen(self)
    }

    case ConnectorInitialized(address) => {
      this.address = address
      startupFuture ! address
      notifyListeners(RemoteServerStarted(transport))
    }

    case ConnectorFailed(reason) => {
      notifyListeners(RemoteServerError(reason, transport))
      startupFuture ! Status.Failure(reason)
    }


    // This is not supported as these will be removed from API
    //case ShutdownEndpoint(address) ⇒
    //case RestartEndpoint(address)  ⇒
    case s @ Send(message, senderOption, recipientRef) ⇒ {
      val recipientAddress = recipientRef.path.address

      endpoints.getEndpointWithPolicy(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒ endpoint ! s
        case Some(Failed(timeOfFailure)) ⇒ if (retryLatchOpen(timeOfFailure)) {
          val endpoint = createEndpoint(recipientAddress, None)
          endpoints.markPass(endpoint)
          endpoint ! s
        } else {
          log.warning("Endpoint failed earlier and retry latch is not open yet; dropping message: {}", message)
          extendedSystem.deadLetters ! message
        }
        case None ⇒ {
          val endpoint = createEndpoint(recipientAddress, None)
          endpoints.registerEndpoint(recipientAddress, endpoint)
          endpoint ! s
        }
      }

    }

    case IncomingConnection(handle) ⇒ {
      val endpoint = createEndpoint(handle.remoteAddress, Some(handle))
      if (settings.UsePassiveConnections) {
        endpoints.registerEndpoint(address, endpoint)
      }
    }

    case Terminated(endpoint) ⇒ {
      //TODO: add real time of failure
      //TODO: Terminate does NOT euqal failed!
      //endpoints.markFailed(endpoint, 0)
      endpoints.remove(endpoint)
    }
  }

  private def createEndpoint(remote: Address, handleOption: Option[TransportConnectorHandle]) = {
    val endpoint = context.actorOf(Props(new EndpointActor(
      provider,
      address,
      remote,
      connector,
      transport,
      settings,
      handleOption)))
    context.watch(endpoint)
  }

  // TODO: de-correlate retries
  private def retryLatchOpen(timeOfFailure: Deadline) = (Deadline.now + settings.RetryLatchClosedFor).isOverdue()

  override def postStop() {
    try {
      connector.shutdown()
      notifyListeners(RemoteServerShutdown(transport))
    } catch {
      case NonFatal(e) => {
        notifyListeners(RemoteServerError(e, transport))
        log.error(e, "Unable to shut down the underlying TransportConnector")
      }
    }
  }

}

object EndpointActor {
  case object AttemptConnect

  sealed trait EndpointState
  case object WaitConnect extends EndpointState
  case object Connected extends EndpointState

  sealed trait EndpointData
  case class Transient(queue: List[Send]) extends EndpointData
  case class Handle(handle: TransportConnectorHandle) extends EndpointData
}

class EndpointException(remoteAddress: Address, msg: String, cause: Throwable) extends Exception(msg + "; remoteAddress = " + remoteAddress, cause)
class EndpointWriteException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointCloseException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointOpenException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)

class EndpointActor(
  val provider: RemoteActorRefProvider,
  val address: Address,
  val remoteAddress: Address,
  val connector: TransportConnector,
  val transport: RemoteTransport,
  val settings: ActorManagedRemotingSettings,
  handleOption: Option[TransportConnectorHandle]) extends Actor
  with RemoteMessageDispatchHelper
  with FSM[EndpointActor.EndpointState, EndpointActor.EndpointData]
  with LifeCycleNotificationHelper {

  import EndpointActor._
  import actmote.TransportConnector._

  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]

  override val log = Logging(context.system.eventStream, "EndpointActor(remote = " + remoteAddress + ")")
  val isServer = handleOption.isDefined

  val queueLimit: Int = settings.PreConnectBufferSize

  def notifyError(reason: Throwable) {
    if (isServer) {
      notifyListeners(RemoteServerError(reason, transport))
    } else {
      notifyListeners(RemoteClientError(reason, transport, remoteAddress))
    }
  }

  handleOption match {
    case Some(handle) ⇒ {
      startWith(Connected, Handle(handle))
      handle.open(self)
      notifyListeners(RemoteServerClientConnected(transport, Some(remoteAddress)))
    }
    case None ⇒ {
      notifyListeners(RemoteClientStarted(transport, remoteAddress))
      startWith(WaitConnect, Transient(Nil))
      self ! AttemptConnect
    }
  }

  when(WaitConnect) {
    case Event(AttemptConnect, _) ⇒ attemptConnect(); stay using stateData

    case Event(s @ Send(msg, _, _), Transient(queue)) ⇒ {
      if (queue.size >= queueLimit) {
        log.warning("Endpoint queue is full; dropping message: {}", msg)
        extendedSystem.deadLetters ! msg
        stay using Transient(queue)
      } else {
        stay using Transient(s :: queue)
      }
    }
    case Event(ConnectionInitialized(handle), _) ⇒ goto(Connected) using Handle(handle)

    case Event(ConnectionFailed(reason), Transient(queue)) ⇒ {
      // Give up
      queue.reverse.foreach { case Send(message, _, _) ⇒ extendedSystem.deadLetters ! message }
      notifyError(reason)
      throw new EndpointOpenException(remoteAddress, "falied to connect", reason)
    }
  }

  onTransition {
    // Send messages that were queued up during connection attempts
    case WaitConnect -> Connected ⇒ (stateData, nextStateData) match {
      case (Transient(queue), Handle(handle)) ⇒ {
        handle.open(self)
        notifyListeners(RemoteClientConnected(transport, remoteAddress))
        log.debug("Sending {} buffered messages to [{}]", queue.size, remoteAddress)
        queue.reverse.foreach { case Send(message, senderOption, recipient) ⇒ handle.write(message, senderOption, recipient) }
      }
      case _ ⇒ //This should never happen
    }
  }

  when(Connected) {
    case Event(MessageArrived(msg), handleState) ⇒ receiveMessage(msg); stay using handleState
    case Event(Send(msg, senderOption, recipient), handleState @ Handle(handle)) ⇒ try {
      if (provider.remoteSettings.LogSend) {
        log.debug("Sending message {} from {} to {}", msg, senderOption, recipient)
      }
      handle.write(msg, senderOption, recipient); stay using handleState
    } catch {
      case NonFatal(reason) ⇒ {
        notifyError(reason)
        throw new EndpointWriteException(remoteAddress, "failed to write to transport", reason)
      }
    }
    case Event(Disconnected(_), handleState) => {
      //TODO: handle disconnects
      stay using handleState
    }
  }

  onTermination {
    case StopEvent(_, Connected, Handle(handle)) ⇒ try {
      log.debug("Shutting down endpoint for [{}]", remoteAddress)

      handle.close()

      if (!isServer) {
        notifyListeners(RemoteClientDisconnected(transport, remoteAddress))
        notifyListeners(RemoteClientShutdown(transport, remoteAddress))
      }

    } catch {
      case NonFatal(reason) ⇒ {
        notifyError(reason)
        log.error(reason, "failure while shutting down endpoint for [{}]", remoteAddress)
      }
    }
  }

  private def attemptConnect() {
    try {
      log.debug("Endpoint connecting to [{}]", remoteAddress)
      connector.connect(remoteAddress, self)
    } catch {
      case NonFatal(reason) ⇒ {
        notifyError(reason)
        throw new EndpointOpenException(remoteAddress, "failed to connect", reason)
      }
    }
  }

}

