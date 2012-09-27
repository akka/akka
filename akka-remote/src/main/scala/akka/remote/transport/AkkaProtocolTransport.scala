package akka.remote.transport

import akka.actor._
import akka.pattern.pipe
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AkkaProtocolTransport._
import akka.remote.transport.AssociationHandle.Disassociated
import akka.remote.transport.AssociationHandle.InboundPayload
import akka.remote.transport.ProtocolStateActor._
import akka.remote.transport.Transport._
import akka.remote.{ FailureDetector, RemoteActorRefProvider }
import akka.util.ByteString
import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

object AkkaProtocolTransport {
  val akkaOverhead: Int = 2000 //Don't know, just a guess

  trait TransportOperation
  case class HandlerRegistered(handler: ActorRef) extends TransportOperation
  case class AssociateUnderlying(remoteAddress: Address, statusPromise: Promise[Status]) extends TransportOperation
  case class ListenUnderlying(listenPromise: Promise[(Address, Promise[ActorRef])])
  case object DisassociateUnderlying extends TransportOperation
}

class AkkaProtocolTransport(
  private val wrappedTransport: Transport,
  private val system: ActorSystemImpl,
  private val codec: AkkaPduCodec) extends Transport {

  //TODO: name should be unique for each wrapped transport
  //TODO: make this the child of someone more appropriate
  private val manager = system.systemActorOf(
    Props(new AkkaProtocolManager(wrappedTransport)),
    "akkaprotocolmanager")

  override val maximumPayloadBytes: Int = wrappedTransport.maximumPayloadBytes - AkkaProtocolTransport.akkaOverhead

  override def listen: Future[(Address, Promise[ActorRef])] = {
    val listenPromise: Promise[(Address, Promise[ActorRef])] = Promise()

    manager ! ListenUnderlying(listenPromise)

    listenPromise.future
  }

  override def associate(remoteAddress: akka.actor.Address): Future[Status] = {
    val statusPromise: Promise[Status] = Promise()

    manager ! AssociateUnderlying(remoteAddress, statusPromise)

    statusPromise.future
  }

  override def shutdown(): Unit = {
    manager ! PoisonPill
  }
}

private[transport] class AkkaProtocolManager(val wrappedTransport: Transport) extends Actor {

  import context.dispatcher

  private val associationHandlerPromise: Promise[ActorRef] = Promise()
  associationHandlerPromise.future.onSuccess {
    case associationHandler: ActorRef ⇒ self ! HandlerRegistered(associationHandler)
  }

  private var associationHandler: ActorRef = _

  def receive: Receive = {
    case ListenUnderlying(listenPromise) ⇒
      val listenFuture = wrappedTransport.listen
      // Receive the address and promise from original transport, then register ourselves as listeners
      // then complete the exposed promise
      listenFuture.onSuccess {
        case (address, wrappedTransportHandlerPromise) ⇒
          wrappedTransportHandlerPromise.success(self)
          listenPromise.success((address, associationHandlerPromise))
      }

      listenFuture.onFailure { case e ⇒ listenPromise.failure(e) }

    case HandlerRegistered(handler) ⇒
      associationHandler = handler
      context.become(ready)
  }

  def ready: Receive = {
    // TODO: create child
    case InboundAssociation(handle)                        ⇒
    case AssociateUnderlying(remoteAddress, statusPromise) ⇒
  }
}

private[transport] class AkkaProtocolHandle(
  val localAddress: Address,
  val remoteAddress: Address,
  val readHandlerPromise: Promise[ActorRef],
  private val wrappedHandle: AssociationHandle,
  private val stateActor: ActorRef,
  private val codec: AkkaPduCodec)
  extends AssociationHandle {

  // TODO: This is currently a hack! The caller should not know anything about the format of the Akka protocol
  // but here it does. This is temporary and will be fixed.
  override def write(payload: ByteString): Boolean = wrappedHandle.write(payload)

  override def disassociate(): Unit = stateActor ! DisassociateUnderlying

}

private[transport] object ProtocolStateActor {
  trait AssociationState
  case object Closed extends AssociationState
  case object WaitActivity extends AssociationState
  case object Open extends AssociationState

  //TODO: replace with FSM timers
  case object HeartbeatTimer

  trait ProtocolStateData
  trait InitialProtocolStateData extends ProtocolStateData

  case class OutboundUnassociated(remoteAddress: Address, statusPromise: Promise[Status], transport: Transport)
    extends InitialProtocolStateData
  case class OutboundUnderlyingAssociated(statusPromise: Promise[Status], wrappedHandle: AssociationHandle)
    extends ProtocolStateData
  case class AssociatedWaitHandler(handlerFuture: Future[ActorRef], wrappedHandle: AssociationHandle, queue: List[ByteString])
    extends ProtocolStateData
  case class HandlerReady(handler: ActorRef, wrappedHandle: AssociationHandle)
    extends ProtocolStateData

  case class InboundUnassociated(associationHandler: ActorRef, wrappedHandle: AssociationHandle)
    extends InitialProtocolStateData
}

private[transport] class ProtocolStateActor(initialData: InitialProtocolStateData,
                                            private val config: RemotingConfig,
                                            private val codec: AkkaPduCodec,
                                            private val failureDetector: FailureDetector)
  extends Actor with FSM[AssociationState, ProtocolStateData] {

  import ProtocolStateActor._
  import context.dispatcher

  // Outbound case
  def this(remoteAddress: Address,
           statusPromise: Promise[Status],
           transport: Transport,
           config: RemotingConfig,
           codec: AkkaPduCodec,
           failureDetector: FailureDetector) = {
    this(OutboundUnassociated(remoteAddress, statusPromise, transport), config, codec, failureDetector)
  }

  // Inbound case
  def this(wrappedHandle: AssociationHandle,
           associationHandler: ActorRef,
           config: RemotingConfig,
           codec: AkkaPduCodec,
           failureDetector: FailureDetector) = {
    this(InboundUnassociated(associationHandler, wrappedHandle), config, codec, failureDetector)
  }

  val provider = context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]
  var timerCancellable: Option[Cancellable] = None

  initialData match {
    case d: OutboundUnassociated ⇒
      d.transport.associate(d.remoteAddress) pipeTo self
      startWith(Closed, d)

    case d: InboundUnassociated ⇒
      d.wrappedHandle.readHandlerPromise.success(self)
      startWith(Closed, d)
  }

  when(Closed) {

    // Transport layer events for outbound associations
    case Event(Invalid, OutboundUnassociated(_, statusPromise, _)) ⇒
      statusPromise.success(Invalid)
      stop()

    case Event(Fail(e), OutboundUnassociated(_, statusPromise, _)) ⇒
      statusPromise.success(Fail(e))
      stop()

    case Event(Ready(wrappedHandle), OutboundUnassociated(_, statusPromise, _)) ⇒
      wrappedHandle.readHandlerPromise.success(self)
      sendAssociate(wrappedHandle)
      failureDetector.heartbeat()
      initTimers()

      if (config.WaitActivityEnabled) {
        goto(WaitActivity) using OutboundUnderlyingAssociated(statusPromise, wrappedHandle)
      } else {
        goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, List.empty)
      }

    // Events for inbound assotiations
    case Event(InboundPayload(p), InboundUnassociated(associationHandler, wrappedHandle)) ⇒
      decodePdu(p) match {
        // After receiving Disassociate we MUST NOT send back a Disassociate (loop)
        case Disassociate ⇒ stop()

        // Incoming association -- implicitly ACK by a heartbeat
        case Associate(cookieOption, origin) ⇒
          if (!config.RequireCookie || cookieOption.getOrElse("") == config.SecureCookie) {
            sendHeartbeat(wrappedHandle)

            failureDetector.heartbeat()
            initTimers()
            goto(Open) using AssociatedWaitHandler(notifyInboundHandler(wrappedHandle, origin, associationHandler), wrappedHandle, List.empty)
          } else {
            stop()
          }

        // Got a stray message -- explicitly reset the association (remote endpoint forced to reassociate)
        case _ ⇒
          sendDisassociate(wrappedHandle)
          stop()

      }

    case Event(DisassociateUnderlying, _) ⇒
      stop()

    case _ ⇒ stay()

  }

  // Timeout of this state is implicitly handled by the failure detector
  when(WaitActivity) {
    case Event(Disassociated, OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      stop()

    case Event(InboundPayload(p), OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        // Any other activity is considered an implicit acknowledgement of the association
        case Message(recipient, serializedMessage, senderOption) ⇒
          sendHeartbeat(wrappedHandle)
          goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, List(p))

        case Heartbeat ⇒
          sendHeartbeat(wrappedHandle)
          failureDetector.heartbeat()
          goto(Open) using AssociatedWaitHandler(notifyOutboundHandler(wrappedHandle, statusPromise), wrappedHandle, List.empty)

        case _ ⇒ goto(Open)
      }

    case Event(HeartbeatTimer, OutboundUnderlyingAssociated(_, wrappedHandle)) ⇒ handleTimers(wrappedHandle)

  }

  when(Open) {
    case Event(Disassociated, AssociatedWaitHandler(handlerFuture, _, _)) ⇒
      stop()

    case Event(Disassociated, HandlerReady(handler, _)) ⇒
      handler ! Disassociated
      stop()

    case Event(InboundPayload(p), AssociatedWaitHandler(handlerFuture, wrappedHandle, queue)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        case Heartbeat ⇒ failureDetector.heartbeat(); stay()

        case Message(recipient, serializedMessage, senderOption) ⇒
          stay() using AssociatedWaitHandler(handlerFuture, wrappedHandle, p :: queue)

        case _ ⇒ stay()
      }

    case Event(InboundPayload(p), HandlerReady(handler, wrappedHandle)) ⇒
      decodePdu(p) match {
        case Disassociate ⇒
          stop()

        case Heartbeat ⇒ failureDetector.heartbeat(); stay()

        case Message(recipient, serializedMessage, senderOption) ⇒
          handler ! InboundPayload(p)
          stay()

        case _ ⇒ stay()
      }

    case Event(HeartbeatTimer, AssociatedWaitHandler(_, wrappedHandle, _))          ⇒ handleTimers(wrappedHandle)
    case Event(HeartbeatTimer, HandlerReady(_, wrappedHandle)) ⇒ handleTimers(wrappedHandle)

    case Event(DisassociateUnderlying, HandlerReady(handler, wrappedHandle)) ⇒
      sendDisassociate(wrappedHandle)
      stop()

    case Event(HandlerRegistered(ref), AssociatedWaitHandler(handlerFuture, wrappedHandle, queue)) ⇒
      queue.reverse.foreach { ref ! InboundPayload(_) }
      stay() using HandlerReady(ref, wrappedHandle)
  }

  def initTimers(): Unit = {
    timerCancellable =
      Some(context.system.scheduler.schedule(config.HeartBeatInterval, config.HeartBeatInterval, self, HeartbeatTimer))
  }

  def handleTimers(wrappedHandle: AssociationHandle): State = {
    if (failureDetector.isAvailable) {
      sendHeartbeat(wrappedHandle)
      stay()
    } else {
      // send disassociate just to be sure
      sendDisassociate(wrappedHandle)
      stop()
    }
  }

  onTermination {
    case StopEvent(FSM.Normal, state, OutboundUnassociated(remoteAddress, statusPromise, transport)) ⇒

    case StopEvent(FSM.Normal, state, OutboundUnderlyingAssociated(statusPromise, wrappedHandle)) ⇒
      statusPromise.success(Fail(new EndpointException("Remote endpoint disassociated in response to associate",
        null)))
      wrappedHandle.disassociate()

    case StopEvent(FSM.Normal, state, AssociatedWaitHandler(handlerFuture, wrappedHandle, queue)) ⇒
      handlerFuture.onSuccess {
        case handler: ActorRef ⇒ handler ! Disassociated
      }
      wrappedHandle.disassociate()

    case StopEvent(FSM.Normal, state, HandlerReady(handler, wrappedHandle)) ⇒
      handler ! Disassociated
      wrappedHandle.disassociate()

    case StopEvent(FSM.Normal, _, InboundUnassociated(_, wrappedHandle)) ⇒
      wrappedHandle.disassociate()
  }

  def notifyOutboundHandler(wrappedHandle: AssociationHandle, statusPromise: Promise[Status]): Future[ActorRef] = {
    val readHandlerPromise: Promise[ActorRef] = Promise()
    readHandlerPromise.future.map { HandlerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(wrappedHandle.localAddress, wrappedHandle.remoteAddress, readHandlerPromise, wrappedHandle, self, codec)

    statusPromise.success(Ready(exposedHandle))
    readHandlerPromise.future
  }

  def notifyInboundHandler(wrappedHandle: AssociationHandle, originAddress: Address, associationHandler: ActorRef): Future[ActorRef] = {
    val readHandlerPromise: Promise[ActorRef] = Promise()
    readHandlerPromise.future.map { HandlerRegistered(_) } pipeTo self

    val exposedHandle =
      new AkkaProtocolHandle(wrappedHandle.localAddress, originAddress, readHandlerPromise, wrappedHandle, self, codec)

    associationHandler ! InboundAssociation(exposedHandle)
    readHandlerPromise.future
  }

  def decodePdu(pdu: ByteString): AkkaPdu = try {
    codec.decodePdu(pdu, provider)
  } catch {
    case NonFatal(e) ⇒ throw new EndpointException("Error while decoding incoming Akka PDU", e)
  }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  def sendHeartbeat(wrappedHandle: AssociationHandle): Unit =
    try {
      wrappedHandle.write(codec.constructHeartbeat)
    } catch {
      case NonFatal(e) ⇒ throw new EndpointException("Error writing tranport", e)
    }

  def sendDisassociate(wrappedHandle: AssociationHandle): Unit =
    try {
      wrappedHandle.write(codec.constructDisassociate)
    } catch {
      case NonFatal(e) ⇒ throw new EndpointException("Error writing tranport", e)
    }

  // Associate should be the first message, so backoff is not needed
  def sendAssociate(wrappedHandle: AssociationHandle): Unit =
    try {
      val cookie = if (config.RequireCookie) Some(config.SecureCookie) else None
      wrappedHandle.write(codec.constructAssociate(cookie, wrappedHandle.localAddress))
    } catch {
      case NonFatal(e) ⇒ throw new EndpointException("Error writing tranport", e)
    }

}
