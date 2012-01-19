/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Promise, Future }
import akka.event.Logging
import akka.util.duration._

private[zeromq] sealed trait PollLifeCycle
private[zeromq] case object NoResults extends PollLifeCycle
private[zeromq] case object Results extends PollLifeCycle
private[zeromq] case object Closing extends PollLifeCycle

private[zeromq] object ConcurrentSocketActor {
  private case object Poll
  private case object ReceiveFrames
  private case object ClearPoll
  private case class PollError(ex: Throwable)

}
private[zeromq] class ConcurrentSocketActor(params: Seq[SocketOption]) extends Actor {

  import ConcurrentSocketActor._
  private val noBytes = Array[Byte]()
  private val zmqContext = {
    params find (_.isInstanceOf[Context]) map (_.asInstanceOf[Context]) getOrElse new Context(1)
  }
  private lazy val deserializer = deserializerFromParams
  private lazy val socket: Socket = socketFromParams
  private lazy val poller: Poller = zmqContext.poller
  private val log = Logging(context.system, this)


  private def handleConnectionMessages: Receive = {
    case Send(frames) ⇒ {
      sendFrames(frames)
      pollAndReceiveFrames()
    }
    case ZMQMessage(frames) ⇒ {
      sendFrames(frames)
      pollAndReceiveFrames()
    }
    case Connect(endpoint) ⇒ {
      socket.connect(endpoint)
      notifyListener(Connecting)
      pollAndReceiveFrames()
    }
    case Bind(endpoint) ⇒ {
      socket.bind(endpoint)
      pollAndReceiveFrames()
    }
    case Subscribe(topic) ⇒ {
      socket.subscribe(topic.toArray)
      pollAndReceiveFrames()
    }
    case Unsubscribe(topic) ⇒ {
      socket.unsubscribe(topic.toArray)
      pollAndReceiveFrames()
    }
  }

  private def handleSocketOption: Receive = {
    case Linger(value)            ⇒ socket.setLinger(value)
    case ReconnectIVL(value)      ⇒ socket.setReconnectIVL(value)
    case Backlog(value)           ⇒ socket.setBacklog(value)
    case ReconnectIVLMax(value)   ⇒ socket.setReconnectIVLMax(value)
    case MaxMsgSize(value)        ⇒ socket.setMaxMsgSize(value)
    case SndHWM(value)            ⇒ socket.setSndHWM(value)
    case RcvHWM(value)            ⇒ socket.setRcvHWM(value)
    case HWM(value)               ⇒ socket.setHWM(value)
    case Swap(value)              ⇒ socket.setSwap(value)
    case Affinity(value)          ⇒ socket.setAffinity(value)
    case Identity(value)          ⇒ socket.setIdentity(value)
    case Rate(value)              ⇒ socket.setRate(value)
    case RecoveryInterval(value)  ⇒ socket.setRecoveryInterval(value)
    case MulticastLoop(value)     ⇒ socket.setMulticastLoop(value)
    case MulticastHops(value)     ⇒ socket.setMulticastHops(value)
    case ReceiveTimeOut(value)    ⇒ socket.setReceiveTimeOut(value)
    case SendTimeOut(value)       ⇒ socket.setSendTimeOut(value)
    case SendBufferSize(value)    ⇒ socket.setSendBufferSize(value)
    case ReceiveBufferSize(value) ⇒ socket.setReceiveBufferSize(value)
    case Linger                   ⇒ sender ! socket.getLinger
    case ReconnectIVL             ⇒ sender ! socket.getReconnectIVL
    case Backlog                  ⇒ sender ! socket.getBacklog
    case ReconnectIVLMax          ⇒ sender ! socket.getReconnectIVLMax
    case MaxMsgSize               ⇒ sender ! socket.getMaxMsgSize
    case SndHWM                   ⇒ sender ! socket.getSndHWM
    case RcvHWM                   ⇒ sender ! socket.getRcvHWM
    case Swap                     ⇒ sender ! socket.getSwap
    case Affinity                 ⇒ sender ! socket.getAffinity
    case Identity                 ⇒ sender ! socket.getIdentity
    case Rate                     ⇒ sender ! socket.getRate
    case RecoveryInterval         ⇒ sender ! socket.getRecoveryInterval
    case MulticastLoop            ⇒ sender ! socket.hasMulticastLoop
    case MulticastHops            ⇒ sender ! socket.getMulticastHops
    case ReceiveTimeOut           ⇒ sender ! socket.getReceiveTimeOut
    case SendTimeOut              ⇒ sender ! socket.getSendTimeOut
    case SendBufferSize           ⇒ sender ! socket.getSendBufferSize
    case ReceiveBufferSize        ⇒ sender ! socket.getReceiveBufferSize
    case ReceiveMore              ⇒ sender ! socket.hasReceiveMore
    case FileDescriptor           ⇒ sender ! socket.getFD
  }

  private def internalMessage: Receive = {
    case Poll ⇒ {
      currentPoll = None
      pollAndReceiveFrames()
    }
    case ReceiveFrames ⇒ {
      receiveFrames() match {
        case Seq()  ⇒
        case frames ⇒ notifyListener(deserializer(frames))
      }
      self ! Poll
    }
    case ClearPoll ⇒ currentPoll = None
    case PollError(ex) ⇒ {
      log.error(ex, "There was a problem polling the zeromq socket")
      self ! Poll
    }
  }

  override def receive: Receive = handleConnectionMessages orElse handleSocketOption orElse internalMessage

  override def preStart {
    setupSocket()
    poller.register(socket, Poller.POLLIN)
    setupConnection()
  }

  private def setupConnection() {
    params filter (_.isInstanceOf[SocketConnectOption]) foreach { self ! _ }
    params filter (_.isInstanceOf[PubSubOption]) foreach { self ! _ }
  }

  private def socketFromParams() = {
    require(ZeroMQExtension.check[SocketType.ZMQSocketType](params), "A socket type is required")
    (params
      find (_.isInstanceOf[SocketType.ZMQSocketType])
      map (t ⇒ zmqContext.socket(t.asInstanceOf[SocketType.ZMQSocketType])) get)
  }

  private def deserializerFromParams = {
    params find (_.isInstanceOf[Deserializer]) map (_.asInstanceOf[Deserializer]) getOrElse new ZMQMessageDeserializer
  }

  private def setupSocket() = {
    params foreach {
      case _: SocketConnectOption | _: PubSubOption | _: SocketMeta ⇒ // ignore, handled differently
      case m ⇒ self ! m
    }
  }

  override def postStop {
    try {
      poller.unregister(socket)
      currentPoll foreach { _ complete Right(Closing) }
      if (socket != null) socket.close
    } finally {
      notifyListener(Closed)
    }
  }

  private def sendFrames(frames: Seq[Frame]) {
    def sendBytes(bytes: Seq[Byte], flags: Int) = socket.send(bytes.toArray, flags)
    val iter = frames.iterator
    while (iter.hasNext) {
      val payload = iter.next.payload
      val flags = if (iter.hasNext) JZMQ.SNDMORE else 0
      sendBytes(payload, flags)
    }
  }

  private var currentPoll: Option[Promise[PollLifeCycle]] = None
  private def pollAndReceiveFrames() {
    if (currentPoll.isEmpty) currentPoll = newEventLoop
  }

  private lazy val eventLoopDispatcher = {
    val fromConfig = params.find(_.isInstanceOf[PollDispatcher]) map {
      option ⇒ context.system.dispatchers.lookup(option.asInstanceOf[PollDispatcher].name)
    }
    fromConfig getOrElse context.system.dispatcher
  }

  private lazy val pollTimeout = {
    val fromConfig = params find (_.isInstanceOf[PollTimeoutDuration]) map (_.asInstanceOf[PollTimeoutDuration].duration)
    fromConfig getOrElse 100.millis
  }

  private def newEventLoop: Option[Promise[PollLifeCycle]] = {
    implicit val executor = eventLoopDispatcher
    Some((Future {
      if (poller.poll(pollTimeout.toMicros) > 0 && poller.pollin(0)) Results else NoResults
    }).asInstanceOf[Promise[PollLifeCycle]] onSuccess {
      case Results   ⇒ self ! ReceiveFrames
      case NoResults ⇒ self ! Poll
      case _         ⇒ self ! ClearPoll
    } onFailure {
      case ex ⇒ self ! PollError(ex)
    })
  }

  private def receiveFrames(): Seq[Frame] = {

    @inline def receiveBytes(): Array[Byte] = socket.recv(0) match {
      case null                                ⇒ noBytes
      case bytes: Array[_] if bytes.length > 0 ⇒ bytes
      case _                                   ⇒ noBytes
    }

    receiveBytes() match {
      case `noBytes` ⇒ Vector.empty
      case someBytes ⇒
        var frames = Vector(Frame(someBytes))
        while (socket.hasReceiveMore) receiveBytes() match {
          case `noBytes` ⇒
          case someBytes ⇒ frames :+= Frame(someBytes)
        }
        frames
    }
  }

  private def notifyListener(message: Any) {
    params find (_.isInstanceOf[Listener]) map (_.asInstanceOf[Listener].listener) foreach { listener ⇒
      if (listener.isTerminated)
        context stop self
      else
        listener ! message
    }
  }
}
