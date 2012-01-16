/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Promise, Future }
import akka.event.Logging

private[zeromq] sealed trait PollLifeCycle
private[zeromq] case object NoResults extends PollLifeCycle
private[zeromq] case object Results extends PollLifeCycle
private[zeromq] case object Closing extends PollLifeCycle

private[zeromq] class ConcurrentSocketActor(params: SocketParameters) extends Actor {

  private val noBytes = Array[Byte]()
  private val socket: Socket = params.context.socket(params.socketType)
  private val poller: Poller = params.context.poller
  private val log = Logging(context.system, this)

  private case object Poll
  private case object ReceiveFrames
  private case object ClearPoll
  private case class PollError(ex: Throwable)
  
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
    case Linger(value) ⇒ socket.setLinger(value)
    case Linger ⇒ sender ! socket.getLinger
    case ReconnectIVL ⇒ sender ! socket.getReconnectIVL
    case ReconnectIVL(value) ⇒ socket.setReconnectIVL(value)
    case Backlog ⇒ sender ! socket.getBacklog
    case Backlog(value) ⇒ socket.setBacklog(value)
    case ReconnectIVLMax ⇒ sender ! socket.getReconnectIVLMax
    case ReconnectIVLMax(value) ⇒ socket.setReconnectIVLMax(value)
    case MaxMsgSize ⇒ sender ! socket.getMaxMsgSize
    case MaxMsgSize(value) ⇒ socket.setMaxMsgSize(value)
    case SndHWM ⇒ sender ! socket.getSndHWM
    case SndHWM(value) ⇒ socket.setSndHWM(value)
    case RcvHWM ⇒ sender ! socket.getRcvHWM
    case RcvHWM(value) ⇒ socket.setRcvHWM(value)
    case HWM(value) ⇒ socket.setHWM(value)
    case Swap ⇒ sender ! socket.getSwap
    case Swap(value) ⇒ socket.setSwap(value)
    case Affinity ⇒ sender ! socket.getAffinity
    case Affinity(value) ⇒ socket.setAffinity(value)
    case Identity ⇒ sender ! socket.getIdentity
    case Identity(value) ⇒ socket.setIdentity(value)
    case Rate ⇒ sender ! socket.getRate
    case Rate(value) ⇒ socket.setRate(value)
    case RecoveryInterval ⇒ sender ! socket.getRecoveryInterval
    case RecoveryInterval(value) ⇒ socket.setRecoveryInterval(value)
    case MulticastLoop ⇒ sender ! socket.hasMulticastLoop
    case MulticastLoop(value) ⇒ socket.setMulticastLoop(value)
    case MulticastHops ⇒ sender ! socket.getMulticastHops
    case MulticastHops(value) ⇒ socket.setMulticastHops(value)
    case ReceiveTimeOut ⇒ sender ! socket.getReceiveTimeOut
    case ReceiveTimeOut(value) ⇒ socket.setReceiveTimeOut(value)
    case SendTimeOut ⇒ sender ! socket.getSendTimeOut
    case SendTimeOut(value) ⇒ socket.setSendTimeOut(value)
    case SendBufferSize ⇒ sender ! socket.getSendBufferSize
    case SendBufferSize(value) ⇒ socket.setSendBufferSize(value)
    case ReceiveBufferSize ⇒ sender ! socket.getReceiveBufferSize
    case ReceiveBufferSize(value) ⇒ socket.setReceiveBufferSize(value)
    case ReceiveMore ⇒ sender ! socket.hasReceiveMore
    case FileDescriptor ⇒ sender ! socket.getFD
  }
  
  private def internalMessage: Receive = {
    case Poll ⇒ {
      currentPoll = None
      pollAndReceiveFrames()
    }
    case ReceiveFrames ⇒ {
      receiveFrames() match {
        case Seq()  ⇒
        case frames ⇒ notifyListener(params.deserializer(frames))
      }
      self ! Poll
    }
    case ClearPoll => currentPoll = None
    case PollError(ex) => {
      log.error(ex, "There was a problem polling the zeromq socket")
      self ! Poll
    }
  }

  override def receive: Receive = handleConnectionMessages orElse handleSocketOption orElse internalMessage

  override def preStart {
    poller.register(socket, Poller.POLLIN)
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

  private def newEventLoop: Option[Promise[PollLifeCycle]] = {
    implicit val executor = params.pollDispatcher getOrElse context.system.dispatcher
    Some((Future {
      if (poller.poll(params.pollTimeoutDuration.toMicros) > 0 && poller.pollin(0)) Results else NoResults
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
    params.listener.foreach { listener ⇒
      if (listener.isTerminated)
        context stop self
      else
        listener ! message
    }
  }
}
