package akka.remote.actmote

import akka.actor._
import akka.event._
import akka.pattern.ask
import akka.pattern.gracefulStop
import akka.remote._
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.util.duration._
import util.control.NonFatal
import concurrent.util.Deadline
import scala.Some
import actmote.TransportConnector._

class ActorManagedRemoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  val HeadActorName = "remoteTransportHeadActor"
  val managedRemoteSettings = new ActorManagedRemotingSettings(provider.remoteSettings.config)

  @volatile var headActor: ActorRef = _
  @volatile var address: Address = _
  @volatile var connector: TransportConnector = _

  def loadConnector = {
    val fqn = managedRemoteSettings.Connector
    val args = Seq(
      classOf[ExtendedActorSystem] -> system,
      classOf[RemoteActorRefProvider] -> provider)

    system.dynamicAccess.createInstanceFor[TransportConnector](fqn, args) match {
      case Left(problem) ⇒ {
        throw new RemoteTransportException("Could not load transport connector " + fqn, problem)
      }
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
          connector = null
          log.info("Remoting stopped successfully")
        }

      } catch {
        case e: akka.pattern.AskTimeoutException ⇒ log.warning("Shutdown timed out")
        case NonFatal(e)                         ⇒ log.error(e, "Shutdown failed")
      }
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  def start() {
    log.info("Starting remoting")
    if (headActor eq null) {
      connector = loadConnector
      val notifier = new DefaultLifeCycleNotifier(provider.remoteSettings, this, system.eventStream, log) // TODO: this uses the logger of this class... might be a problem
      headActor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new HeadActor(provider.remoteSettings, connector, managedRemoteSettings, notifier)), HeadActorName)

      val timeout = new Timeout(managedRemoteSettings.StartupTimeout)
      val addressFuture = headActor.ask(Listen(this))(timeout).mapTo[Address]

      try {
        this.address = Await.result(addressFuture, timeout.duration)
      } catch {
        case e: akka.pattern.AskTimeoutException ⇒ throw new RemoteTransportException("Startup timed out", e)
        case NonFatal(e)                         ⇒ throw new RemoteTransportException("Startup failed", e)
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

trait LifeCycleNotifier {
  def remoteClientError(reason: Throwable, remoteAddress: Address): Unit
  def remoteClientDisconnected(remoteAddress: Address): Unit
  def remoteClientConnected(remoteAddress: Address): Unit
  def remoteClientStarted(remoteAddress: Address): Unit
  def remoteClientShutdown(remoteAddress: Address): Unit

  def remoteServerStarted(): Unit
  def remoteServerShutdown(): Unit
  def remoteServerError(reason: Throwable): Unit
  def remoteServerClientConnected(remoteAddress: Address)
  def remoteServerClientDisconnected(remoteAddress: Address)
  def remoteServerClientClosed(remoteAddress: Address)
}

class DefaultLifeCycleNotifier(remoteSettings: RemoteSettings, remoteTransport: RemoteTransport, eventStream: EventStream, log: LoggingAdapter) extends LifeCycleNotifier {
  //private def useUntrustedMode = remoteSettings.UntrustedMode
  private def logRemoteLifeCycleEvents = remoteSettings.LogRemoteLifeCycleEvents

  protected def notifyListeners(message: RemoteLifeCycleEvent): Unit = {
    eventStream.publish(message)
    if (logRemoteLifeCycleEvents) log.log(message.logLevel, "{}", message)
  }

  def remoteClientError(reason: Throwable, remoteAddress: Address) { notifyListeners(RemoteClientError(reason, remoteTransport, remoteAddress)) }
  def remoteClientDisconnected(remoteAddress: Address) { notifyListeners(RemoteClientDisconnected(remoteTransport, remoteAddress)) }
  def remoteClientConnected(remoteAddress: Address) { notifyListeners(RemoteClientConnected(remoteTransport, remoteAddress)) }
  def remoteClientStarted(remoteAddress: Address) { notifyListeners(RemoteClientStarted(remoteTransport, remoteAddress)) }
  def remoteClientShutdown(remoteAddress: Address) { notifyListeners(RemoteClientShutdown(remoteTransport, remoteAddress)) }

  def remoteServerStarted() { notifyListeners(RemoteServerStarted(remoteTransport)) }
  def remoteServerShutdown() { notifyListeners(RemoteServerShutdown(remoteTransport)) }
  def remoteServerError(reason: Throwable) { notifyListeners(RemoteServerError(reason, remoteTransport)) }
  def remoteServerClientConnected(remoteAddress: Address) { notifyListeners(RemoteServerClientConnected(remoteTransport, Some(remoteAddress))) }
  def remoteServerClientDisconnected(remoteAddress: Address) { notifyListeners(RemoteServerClientDisconnected(remoteTransport, Some(remoteAddress))) }
  def remoteServerClientClosed(remoteAddress: Address) { notifyListeners(RemoteServerClientClosed(remoteTransport, Some(remoteAddress))) }
}

private[actmote] sealed trait RemotingCommand
private[actmote] case class Listen(transport: RemoteTransport)
private[actmote] case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand {
  override def toString = "Remote message " + senderOption.getOrElse("UNKNOWN") + " -> " + recipient
}

object HeadActor {
  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  case class Failed(timeOfFailure: Deadline) extends EndpointPolicy

  // TODO: How to handle passive connections?
  // TODO: Implement pruning of old entries
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

    def removeIfNotLatched(endpoint: ActorRef) {
      // TODO: This is ugly and used only because passive connectins are not handled properly yet...
      if (endpointToAddress.get(endpoint).isDefined) {
        val address = endpointToAddress(endpoint)
        addressToEndpointAndPolicy.get(address) match {
          case Some(Failed(_)) ⇒ //Leave it be. It contains only the last failure time, but not the endpoint ref
          case _               ⇒ addressToEndpointAndPolicy.remove(address)
        }
        // The endpoint is already stopped, always remove it
        endpointToAddress.remove(endpoint)
      }
    }
  }
}

class HeadActor(
  val remoteSettings: RemoteSettings,
  val connector: TransportConnector,
  val settings: ActorManagedRemotingSettings,
  val notifier: LifeCycleNotifier) extends Actor {

  import akka.actor.SupervisorStrategy._
  import actmote.TransportConnector.IncomingConnection
  import HeadActor._

  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val eventStream = context.system.eventStream
  val log = Logging(eventStream, "HeadActor")

  private var address: Address = _
  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  val endpoints = new EndpointRegistry()

  private var transport: RemoteTransport = _
  private var startupFuture: ActorRef = _

  private val retryLatchEnabled = settings.RetryLatchClosedFor.length > 0

  private def failureStrategy = if (!retryLatchEnabled) {
    // This strategy keeps all the messages in the stash of the endpoint so restart will transfer the queue
    // to the restarted endpoint -- thus no messages are lost
    Restart
  } else {
    // This strategy throws away all the messages enqueued in the endpoint (in its stash), registers the time of failure,
    // keeps throwing away messages until the retry latch becomes open (RetryLatchClosedFor)
    endpoints.markFailed(sender, Deadline.now)
    Stop
  }

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: EndpointException ⇒ failureStrategy
    case NonFatal(_)          ⇒ failureStrategy
  }

  def receive = {
    case Listen(transport) ⇒ {
      this.transport = transport
      startupFuture = sender
      connector.listen(self)
    }

    case ConnectorInitialized(address) ⇒ {
      this.address = address
      startupFuture ! address
      notifier.remoteServerStarted()
    }

    case ConnectorFailed(reason) ⇒ {
      notifier.remoteServerError(reason)
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
      endpoints.removeIfNotLatched(endpoint)
    }
  }

  private def createEndpoint(remoteAddress: Address, handleOption: Option[TransportConnectorHandle]) = {
    val endpoint = context.actorOf(Props(new EndpointActor(
      notifier,
      connector,
      remoteSettings,
      settings,
      address,
      remoteAddress,
      handleOption)).withDispatcher("akka.actor.default-stash-dispatcher"))
    context.watch(endpoint)
  }

  // TODO: de-correlate retries
  private def retryLatchOpen(timeOfFailure: Deadline) = (Deadline.now + settings.RetryLatchClosedFor).isOverdue()

  override def postStop() {
    try {
      connector.shutdown()
      notifier.remoteServerShutdown()
    } catch {
      case NonFatal(e) ⇒ {
        notifier.remoteServerError(e)
        log.error(e, "Unable to shut down the underlying TransportConnector")
      }
    }
  }

}

object EndpointActor {
  case object AttemptConnect
}

class EndpointException(remoteAddress: Address, msg: String, cause: Throwable) extends Exception(msg + "; remoteAddress = " + remoteAddress, cause)
class EndpointWriteException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointCloseException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointOpenException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointConnectionFailedException(remoteAddress: Address, msg: String, cause: Throwable) extends EndpointException(remoteAddress, msg, cause)
class EndpointConnectorProtocolViolated(remoteAddress: Address, msg: String) extends EndpointException(remoteAddress, msg, null)

class EndpointActor(
  val notifier: LifeCycleNotifier,
  val connector: TransportConnector,
  val remoteSettings: RemoteSettings,
  val settings: ActorManagedRemotingSettings,
  val address: Address,
  val remoteAddress: Address,
  private var handleOption: Option[TransportConnectorHandle]) extends Actor with Stash {

  import EndpointActor._

  val extendedSystem = context.system.asInstanceOf[ExtendedActorSystem]
  val eventStream = extendedSystem.eventStream

  val log = Logging(eventStream, "EndpointActor(remote = " + remoteAddress + ")")
  val isServer = handleOption.isDefined

  // TODO: Propagate it to the stash size
  val queueLimit: Int = settings.PreConnectBufferSize

  private def notifyError(reason: Throwable) {
    if (isServer) {
      notifier.remoteServerError(reason)
    } else {
      notifier.remoteClientError(reason, remoteAddress)
    }
  }

  override def postRestart(reason: Throwable) {
    // Clear handle to force reconnect
    handleOption = None
    initialize()
  }

  override def preStart {
    initialize()
  }

  private def initialize() {
    handleOption match {
      case Some(handle) ⇒ {
        handle.open(self)
        notifier.remoteServerClientConnected(remoteAddress)
        context.become(connected)
      }
      case None ⇒ {
        notifier.remoteClientStarted(remoteAddress)
        //TODO: Do this only when not restarting a passive connection
        self ! AttemptConnect
      }
    }
  }

  // Unconnected state
  def receive = {
    case AttemptConnect      ⇒ attemptConnect()
    case s @ Send(msg, _, _) ⇒ stash()
    case ConnectionInitialized(handle) ⇒ {
      handleOption = Some(handle)
      handle.open(self)
      // Requeue all stored send messages
      unstashAll()
      notifier.remoteClientConnected(remoteAddress)
      context.become(connected)
    }
    case ConnectionFailed(reason) ⇒ {
      // Give up
      throw new EndpointOpenException(remoteAddress, "falied to connect", reason)
    }
  }

  // Connected state
  def connected: Receive = {
    case Send(msg, senderOption, recipient) ⇒ try {
      if (remoteSettings.LogSend) {
        log.debug("Sending message {} from {} to {}", msg, senderOption, recipient)
      }
      handleOption.foreach { _.write(msg, senderOption, recipient) }
    } catch {
      case NonFatal(reason) ⇒ {
        // We do not retry messages, so we drop attempt
        // the message may still got through, so we do not send to deadLetters either
        notifyError(reason)
        throw new EndpointWriteException(remoteAddress, "failed to write to transport", reason)
      }
    }
    case ConnectionFailed(reason) ⇒ {
      throw new EndpointConnectionFailedException(remoteAddress, "endpoint failed", reason)
    }
    case d @ Disconnected(_) ⇒ {
      context.stop(self)
    }
  }

  override def postStop {
    try {
      log.debug("Shutting down endpoint for [{}]", remoteAddress)

      handleOption foreach { _.close() }

      if (!isServer) {
        notifier.remoteClientDisconnected(remoteAddress)
        notifier.remoteClientShutdown(remoteAddress)
      }

    } catch {
      case NonFatal(reason) ⇒ {
        notifyError(reason)
        log.error(reason, "failure while shutting down endpoint for [{}]", remoteAddress)
      }
    }
  }

  override def unhandled(message: Any) {
    throw new EndpointConnectorProtocolViolated(remoteAddress, "Endpoint <-> Connector protocol violated; unexpected message: " + message)
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

