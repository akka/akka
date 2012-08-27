package akka.remote.actmote

import akka.actor._
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.pattern.gracefulStop
import akka.remote._
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.util.duration._
import util.control.NonFatal

// TODO: Add notifications
// TODO: Log meaningful event -> check old remoting code
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
        if (Await.result(stopped, 6 seconds)) {
          headActor = null
          transport = null
        }
      } catch {
        case e: akka.pattern.AskTimeoutException ⇒ // the actor wasn't stopped within 5 seconds
      }
    }
  }

  // Start assumes that it cannot be followed by another start() without having a shutdown() first
  def start() {
    if (headActor eq null) {
      transport = loadTransport
      headActor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new HeadActor(provider, transport, managedRemoteSettings)), HeadActorName)

      val timeout = new Timeout(5 seconds)
      val addressFuture = headActor.ask(Listen)(timeout).mapTo[Address]

      this.address = Await.result(addressFuture, timeout.duration)
      notifyListeners(RemoteServerStarted(this))
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

private[actmote] sealed trait RemotingCommand
private[actmote] case object Listen
// No longer needed, if shutdownClient and restartClient are removed from RemoteTransport API
//private[actmote] case class ShutdownEndpoint(address: Address) extends RemotingCommand
//private[actmote] case class RestartEndpoint(address: Address) extends RemotingCommand
private[actmote] case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand

object HeadActor {
  sealed trait EndpointPolicy
  case class Pass(endpoint: ActorRef) extends EndpointPolicy
  // TODO: what type should be used for points in time?
  case class Failed(timeOfFailure: Long) extends EndpointPolicy
}

// TODO: HeadActor MUST WATCH his endpoint Actors
class HeadActor(
  val provider: RemoteActorRefProvider,
  val transport: TransportConnector,
  val settings: ActorManagedRemotingSettings) extends Actor {

  import akka.actor.SupervisorStrategy._
  import actmote.TransportConnector.IncomingConnection
  import HeadActor._

  private var address: Address = _
  // Mapping between addresses and endpoint actors. If passive connections are turned off, incoming connections
  // will be not part of this map!
  private val clientTable = scala.collection.mutable.Map[Address, EndpointPolicy]()

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: EndpointException ⇒ Restart
  }

  def receive = {
    case Listen ⇒ sender ! listen
    // This is not supported as these calls are only coming
    //case ShutdownEndpoint(address) ⇒
    //case RestartEndpoint(address)  ⇒
    case s @ Send(message, senderOption, recipientRef) ⇒ {
      val recipientAddress = recipientRef.path.address

      clientTable.get(recipientAddress) match {
        case Some(Pass(endpoint)) ⇒ endpoint ! s
        case Some(Failed(timeOfFailure)) ⇒ if (retryLatchOpen(timeOfFailure)) {
          val endpoint = createEndpoint(recipientAddress, None)
          clientTable(recipientAddress) = Pass(endpoint)
          endpoint ! s
        } else { /* TODO: Retry latch is not open yet, send message to dead letters */ }
        case None ⇒ {
          val endpoint = createEndpoint(recipientAddress, None)
          clientTable += recipientAddress -> Pass(endpoint)
          endpoint ! s
        }
      }

    }
    case IncomingConnection(handle) ⇒ {
      val endpoint = createEndpoint(handle.remoteAddress, Some(handle))
      handle.responsibleActor = endpoint
      if (settings.UsePassiveConnections)
        clientTable += handle.remoteAddress -> Pass(endpoint)
    }
  }

  private def listen = {
    transport.responsibleActor = self
    // TODO: rename transport call address to listen, and add startup semanticss
    address = transport.address
    address
  }

  private def createEndpoint(remote: Address, handleOption: Option[TransportConnectorHandle]) = {
    context.actorOf(Props(new EndpointActor(
      provider,
      address,
      remote,
      transport,
      settings,
      handleOption)))
  }

  // TODO: implement this and make configurable
  private def retryLatchOpen(timeOfFailure: Long) = false

  override def postStop() {
    // TODO: All the children actors are stopped already?
    transport.shutdown()
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

class EndpointException(msg: String) extends Exception(msg)

// TODO: Error handling (borked connection, etc...), handling closed connections
class EndpointActor(
  val provider: RemoteActorRefProvider,
  val address: Address,
  val remoteAddress: Address,
  val transport: TransportConnector,
  val settings: ActorManagedRemotingSettings,
  handleOption: Option[TransportConnectorHandle]) extends Actor
  with RemoteMessageDispatchHelper
  with FSM[EndpointActor.EndpointState, EndpointActor.EndpointData] {

  import EndpointActor._
  import actmote.TransportConnector._

  override val log = Logging(context.system.eventStream, "EndpointActor(remote = " + remoteAddress + ")")

  val useUntrustedMode: Boolean = provider.remoteSettings.UntrustedMode

  handleOption match {
    case Some(handle) ⇒ {
      startWith(Connected, Handle(handle))
      registerReadCallback(handle)
    }
    case None ⇒ {
      startWith(WaitConnect, Transient(Nil))
      self ! AttemptConnect
    }
  }

  // TODO: Limit queue
  when(WaitConnect) {
    case Event(AttemptConnect, _)                   ⇒ attemptConnect(); stay using stateData
    // TODO: log send if it is configured
    case Event(s @ Send(_, _, _), Transient(queue)) ⇒ stay using Transient(s :: queue)
    case Event(ConnectionInitialized(handle), _)    ⇒ goto(Connected) using Handle(handle)
    case Event(ConnectionFailed(reason), _) ⇒ {
      // Give up
      //TODO: log reason
      //TODO: send messages to deadLetters
      //TODO: limit retries
      attemptConnect()
      stay using stateData
    }
  }

  onTransition {
    // Send messages that were queued up during connection attempts
    case WaitConnect -> Connected ⇒ (stateData, nextStateData) match {
      case (Transient(queue), Handle(handle)) ⇒ {
        registerReadCallback(handle)
        queue.reverse.foreach { case Send(message, senderOption, recipient) ⇒ handle.write(message, senderOption, recipient) }
      }
      case _ ⇒ //This should never happen
    }
  }

  when(Connected) {
    case Event(MessageArrived(msg), handleState)                                 ⇒ receiveMessage(msg); stay using handleState
    case Event(Send(msg, senderOption, recipient), handleState @ Handle(handle)) ⇒ handle.write(msg, senderOption, recipient); stay using handleState
  }

  onTermination {
    case StopEvent(_, Connected, Handle(handle)) ⇒ try {
      handle.close()
    } catch {
      case NonFatal(e) ⇒ log.error(e, "failure while shutting down [{}]", remoteAddress)
    }
  }

  private def attemptConnect() {
    try {
      transport.connect(remoteAddress, self)
    } catch {
      case e: Exception ⇒ throw new RemoteTransportException("Unexpected error while trying to connect to " + remoteAddress, e)
    }
  }

  private def registerReadCallback(handle: TransportConnectorHandle) {
    handle.responsibleActor = self
  }

}

