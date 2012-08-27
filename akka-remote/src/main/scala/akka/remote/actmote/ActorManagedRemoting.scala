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

// TODO: Add notifications
class ActorManagedRemoting(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider) extends RemoteTransport(_system, _provider) {

  val HeadActorName = "remoteTransportHeadActor"
  val managedRemoteSettings = new ActorManagedRemotingSettings(provider.remoteSettings.config.getConfig("akka.remote.managed"))

  @volatile var headActor: ActorRef = _ // TODO: make this threadsafe and think about startup sequence
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

  def log: LoggingAdapter = Logging(system.eventStream, "ActorManagedRemoting(" + address + ")")

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

  def start() {
    transport = loadTransport
    // This is not very threadsafe, but multiple concurrent calls to start() should not happen in practice
    if (headActor eq null) {
      // TODO: reuse passive connections must be configurable
      headActor = system.asInstanceOf[ActorSystemImpl].systemActorOf(Props(new HeadActor(provider, transport, true)), HeadActorName)

      val timeout = new Timeout(5 seconds)
      val addressFuture = headActor.ask(Listen)(timeout).mapTo[Address]

      this.address = Await.result(addressFuture, timeout.duration)
      notifyListeners(RemoteServerStarted(this))
    }
  }

  def shutdownClientConnection(address: Address) {
    headActor ! ShutdownEndpoint(address)
  }

  def restartClientConnection(address: Address) {
    headActor ! RestartEndpoint(address)
  }

  def send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) {
    headActor ! Send(message, senderOption, recipient)
  }

  protected def useUntrustedMode = false // TODO: Comes from configuration

  protected def logRemoteLifeCycleEvents = false //TODO: Comes from configuration

}

// TODO: get useful names
private[actmote] sealed trait RemotingCommand
private[actmote] case object Listen
private[actmote] case class ShutdownEndpoint(address: Address) extends RemotingCommand
private[actmote] case class RestartEndpoint(address: Address) extends RemotingCommand
private[actmote] case class Send(message: Any, senderOption: Option[ActorRef], recipient: RemoteActorRef) extends RemotingCommand

class UnexpectedException(cause: Throwable) extends Exception("Unexpected exception received from transport layer", cause)

// HeadActor MUST WATCH his endpoint Actors
class HeadActor(val provider: RemoteActorRefProvider, val transport: TransportConnector, val usePassiveConnections: Boolean) extends Actor {
  private var address: Address = _
  private val clientTable = scala.collection.mutable.Map[Address, ActorRef]()
  // TODO: Is this necessarily needed
  private val endpoints = scala.collection.mutable.Set[ActorRef]()

  import akka.actor.SupervisorStrategy._
  import actmote.TransportConnector.IncomingConnection

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
    case _: UnexpectedException ⇒ Restart
  }

  def receive = {
    case Listen                    ⇒ sender ! listen
    // TODO: Passive clients should NOT close the connection
    case ShutdownEndpoint(address) ⇒ clientTable.remove(address).foreach(context.stop(_)) // Need separate table for ALL endpoints, and usable endpoints
    case RestartEndpoint(address)  ⇒ // TODO: Not yet supported
    case s @ Send(message, senderOption, recipientRef) ⇒ {
      val recipientAddress = recipientRef.path.address

      clientTable.get(recipientAddress) match {
        case Some(endpoint) ⇒ endpoint ! s
        case None ⇒ {
          val endpoint = createEndpoint(recipientAddress, None)
          clientTable += recipientAddress -> endpoint
          endpoint ! s
        }
      }

    }
    case IncomingConnection(handle) ⇒ {
      val endpoint = createEndpoint(handle.remoteAddress, Some(handle))
      handle.responsibleActor = endpoint
      if (usePassiveConnections)
        clientTable += handle.remoteAddress -> endpoint
    }
  }

  private def listen = {
    transport.responsibleActor = self
    address = transport.address
    address
  }

  private def createEndpoint(remote: Address, handleOption: Option[TransportConnectorHandle]) = {
    // TODO:
    context.actorOf(Props(new EndpointActor(
      provider,
      address,
      remote,
      transport,
      handleOption)))
  }

  override def postStop() {
    clientTable.values.foreach(context.stop(_))
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

// TODO: Error handling (borked connection, etc...), handling closed connections
class EndpointActor(val provider: RemoteActorRefProvider, val address: Address, val remoteAddress: Address, val transport: TransportConnector, handleOption: Option[TransportConnectorHandle]) extends Actor
  with RemoteMessageDispatchHelper
  with FSM[EndpointActor.EndpointState, EndpointActor.EndpointData] {

  import EndpointActor._
  import actmote.TransportConnector._

  override val log = Logging(context.system.eventStream, "EndpointActor(remote = " + remoteAddress + ")")
  def useUntrustedMode: Boolean = false //TODO: coming from configuration

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

  // TODO: Limit queue, make timeout configurable
  when(WaitConnect) {
    case Event(AttemptConnect, _)                   ⇒ attemptConnect(); stay using stateData
    // TODO: log send if it is configured
    case Event(s @ Send(_, _, _), Transient(queue)) ⇒ stay using Transient(s :: queue)
    case Event(ConnectionInitialized(handle), _)    ⇒ goto(Connected) using Handle(handle)
    case Event(ConnectionFailed(reason), _) ⇒ {
      //TODO: log reason
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
    case StopEvent(_, Connected, Handle(handle)) ⇒ handle.close()
  }

  private def attemptConnect() {
    try {
      transport.connect(remoteAddress, self)
    } catch {
      // TODO: throw TransportException
      case e: Exception ⇒ throw new UnexpectedException(e)
    }
  }

  private def registerReadCallback(handle: TransportConnectorHandle) {
    handle.responsibleActor = self
  }

}

