package akka.remote.transport

import akka.actor._
import akka.pattern.pipe
import akka.remote.RemoteProtocol.MessageProtocol
import akka.remote.transport.AkkaPduCodec._
import akka.remote.transport.AssociationHandle._
import akka.remote.transport.EndpointReader._
import akka.remote.transport.EndpointWriter.{Ready, BackoffOver, Send, Retire}
import akka.remote.{FailureDetector, MessageSerializer, RemoteActorRefProvider, PhiAccrualFailureDetector}
import akka.serialization.Serialization
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit._
import scala.concurrent.util.{Duration, FiniteDuration}
import scala.util.control.NonFatal
import akka.util.ByteString
import akka.AkkaException

// TODO: doc defaults in reference.conf
// TODO: origin handling
class RemotingConfig(config: Config) {

  import config._

  val FailureDetectorThreshold: Double = getDouble("akka.remoting.failure-detector.threshold")

  val FailureDetectorMaxSampleSize: Int = getInt("akka.remoting.failure-detector.max-sample-size")

  val FailureDetectorStdDeviation: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.min-std-deviation"), MILLISECONDS)

  val AcceptableHeartBeatPause: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.failure-detector.acceptable-heartbeat-pause"), MILLISECONDS)

  val HeartBeatInterval: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.heartbeat-interval"), MILLISECONDS)

  val WaitActivityEnabled: Boolean = getBoolean("akka.remoting.wait-activity-enabled")

  val BackoffPeriod: FiniteDuration =
    Duration(getMilliseconds("akka.remoting.backoff-interval"), MILLISECONDS)

  val RequireCookie: Boolean = getBoolean("akka.remoting.require-cookie")

  val SecureCookie: String = getString("akka.remoting.secure-cookie")

}

// better name
trait MessageDispatcher {
  def dispatch(recipient: ActorRef, serializedMessage: Any, senderOption: Option[ActorRef]): Unit
}

class DefaultMessageDispatcher extends MessageDispatcher {
  def dispatch(recipient: ActorRef, serializedMessage: Any, senderOption: Option[ActorRef]): Unit = ???
}

object EndpointWriter {

  trait ProtocolStateEvent
  case object Retire extends ProtocolStateEvent
  case object Ready extends ProtocolStateEvent

  case class Send(msg: Any, recipient: ActorRef, senderOption: Option[ActorRef])

  case object BackoffOver
}

class EndpointException(msg: String, cause: Throwable) extends AkkaException(msg, cause)
class InvalidAssociation(remoteAddress: Address) extends EndpointException(s"Invalid address: $remoteAddress", null)

//TODO: add configurable dispatcher
//TODO: queue if WaitActivity is enabled
//TODO: notify if queue is getting close to filled
//TODO: log watermarks
class EndpointWriter(
                      val active: Boolean,
                      handleOption: Option[AssociationHandle],
                      val remoteAddress: Address,
                      val transport: Transport,
                      val config: RemotingConfig,
                      val codec: AkkaPduCodec) extends Actor with Stash with FSM[AssociationState, Unit] {

  val extendedSystem: ExtendedActorSystem = context.system.asInstanceOf[ExtendedActorSystem]
  var reader: ActorRef = null
  var handle: AssociationHandle = null
  var buffering = true
  val msgDispatch = new DefaultMessageDispatcher

  import context.dispatcher

  if (active) {
    transport.associate(remoteAddress) pipeTo self
  } else {
    handleOption match {
      case Some(h) => handle = h
      case None => throw new EndpointException("Passive connections need an already associated handle injected", null)
    }
    startReadEndpoint()
  }



//  def receive: Receive = {
//    case Send(msg, recipient, senderOption) => if (!buffering) {
//      sendMessage(msg, recipient, senderOption)
//    } else {
//      stash()
//    }
//
//    case Transport.Invalid => throw new InvalidAssociation(remoteAddress)
//
//    case Transport.Fail(e) => throw new EndpointException(s"Association failed with $remoteAddress", e)
//
//    case Transport.Ready(inboundHandle) =>
//      handle = Some(inboundHandle)
//      onBufferingOver()
//
//    case BackoffOver => onBufferingOver()
//
//    case Ready => onBufferingOver()
//
//    case Retire => context.stop(self)
//
//  }

  def startReadEndpoint(): Unit = {
    onBufferingOver()

    val failureDetector = new PhiAccrualFailureDetector(
      threshold = config.FailureDetectorThreshold,
      maxSampleSize = config.FailureDetectorMaxSampleSize,
      minStdDeviation = config.FailureDetectorStdDeviation,
      acceptableHeartbeatPause = config.AcceptableHeartBeatPause,
      firstHeartbeatEstimate = config.HeartBeatInterval
    )

    reader = context.actorOf(Props(
        new EndpointReader(active, handle, self, config, codec, failureDetector, msgDispatch)
      ), "reader")
  }

  def onBufferingOver(): Unit = {
    buffering = false; unstashAll()
  }

  def backoff(): Unit = {
    buffering = true
    context.system.scheduler.scheduleOnce(config.BackoffPeriod, self, BackoffOver)
  }

  def serializeMessage(msg: Any): MessageProtocol = {
    Serialization.currentTransportAddress.withValue(handle.localAddress) {
      (MessageSerializer.serialize(extendedSystem, msg.asInstanceOf[AnyRef]))
    }
  }

  def sendMessage(msg: Any, recipient: ActorRef, senderOption: Option[ActorRef]): Unit = {
    val pdu = codec.constructMessagePdu(handle.localAddress, recipient, serializeMessage(msg), senderOption)
    try {
      if (!handle.write(pdu)) {
        stash()
        backoff()
      }
    } catch {
      case NonFatal(e) => throw new EndpointException("Failed to write message to the transport", e)
    }

  }

}

object EndpointReader {

  trait AssociationState
  case object Closed extends AssociationState
  case object WaitActivity extends AssociationState
  case object Open extends AssociationState

  case object HeartbeatTimer

}

//TODO: logging
class EndpointReader(
                      val active: Boolean,
                      val handle: AssociationHandle,
                      val writer: ActorRef,
                      val config: RemotingConfig,
                      val codec: AkkaPduCodec,
                      val failureDetector: FailureDetector,
                      val msgDispatch: MessageDispatcher) extends Actor with FSM[AssociationState, Unit]{

  import context.dispatcher

  val provider = context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider]
  var timerCancellable: Option[Cancellable] = None

  handle.readHandlerPromise.success(self)

  if (active) {
    sendAssociate()
    failureDetector.heartbeat()
    initTimers()

    if (config.WaitActivityEnabled) {
      startWith(WaitActivity, ())
    } else {
      writer ! Ready
      startWith(Open, ())
    }

  } else {
    startWith(Closed, ())
  }

  when(Closed) {
    case Event(Disassociated, _) => retire(); stay()

    case Event(InboundPayload(p), _) =>
      decodePdu(p) match {
        // After receiving Disassociate we MUST NOT send back a Disassociate (loop)
        case Disassociate => retire(); stay()

        // Incoming association -- implicitly ACK by a heartbeat
        case Associate(cookieOption, origin) =>
          if (!config.RequireCookie || cookieOption.getOrElse("") == config.SecureCookie) {
            goto(Open)
          } else {
           stay()
          }

        // Got a stray message -- explicitly reset the association (remote endpoint forced to reassociate)
        case _ => sendDisassociate(); stay()

      }

    case _ => stay()

  }

  // Timeout of this state is imlplicitly handled by the failure detector
  when(WaitActivity) {
    case Event(Disassociated, _) => goto(Closed)

    case Event(InboundPayload(p), _) =>
      decodePdu(p) match {
        case Disassociate => goto(Closed)

        // Any other activity is considered an implicit acknowledgement of the association
        case Message(recipient, serializedMessage, senderOption) =>
          msgDispatch.dispatch(recipient, serializedMessage, senderOption)
          goto(Open)

        case Heartbeat => failureDetector.heartbeat(); goto(Open)

        case _ => goto(Open)
      }

    case Event(HeartbeatTimer, _) => handleTimers
  }

  when(Open) {
    case Event(Disassociated, _) => goto(Closed)

    case Event(InboundPayload(p), _) =>
      decodePdu(p) match {
        case Disassociate => goto(Closed)

        case Heartbeat => failureDetector.heartbeat(); stay()

        case Message(recipient, serializedMessage, senderOption) =>
          msgDispatch.dispatch(recipient, serializedMessage, senderOption)
          stay()

        case _ => stay()
      }

    case Event(HeartbeatTimer, _) => handleTimers
  }

  onTransition {
    case _ -> Closed => retire()

    case Closed -> Open =>
      sendAssociate()
      failureDetector.heartbeat()
      initTimers()
      writer ! Ready

    case WaitActivity -> Open =>
      sendHeartbeat()
      writer ! Ready
  }

  def initTimers(): Unit = {
    timerCancellable =
      Some(context.system.scheduler.schedule(config.HeartBeatInterval, config.HeartBeatInterval, self, HeartbeatTimer))
  }

  def handleTimers: State = {
    if (failureDetector.isAvailable) {
      sendHeartbeat()
      stay()
    } else {
      // send disassociate just to be sure
      sendDisassociate()
      goto(Closed)
    }
  }



  def decodePdu(pdu: ByteString): AkkaPdu = try {
    codec.decodePdu(pdu, provider)
  } catch {
    case NonFatal(e) => throw new EndpointException("Error while decoding incoming Akk PDU", e)
  }

  // Neither heartbeats neither disassociate cares about backing off if write fails:
  //  - Missing heartbeats are not critical
  //  - Disassociate messages are not guaranteed anyway
  def sendHeartbeat(): Unit =
    try {
      handle.write(codec.constructHeartbeat)
    } catch {
      case NonFatal(e) => throw new EndpointException("Error writing tranport", e)
    }

  def sendDisassociate(): Unit =
    try {
      handle.write(codec.constructDisassociate)
    } catch {
      case NonFatal(e) => throw new EndpointException("Error writing tranport", e)
    }

  // Associate should be the first message, so backoff is not needed
  def sendAssociate(): Unit =
    try {
      val cookie = if (config.RequireCookie) Some(config.SecureCookie) else None
      handle.write(codec.constructAssociate(cookie, handle.localAddress))
    } catch {
      case NonFatal(e) => throw new EndpointException("Error writing tranport", e)
    }

  def retire() {
    writer ! Retire
    timerCancellable foreach { _.cancel() }
    //handle.disassociate() //TODO: should be done by writer
  }

}
