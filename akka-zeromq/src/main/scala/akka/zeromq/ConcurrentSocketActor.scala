/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Await, Promise, Dispatchers, Future }
import akka.util.duration._

private[zeromq] sealed trait PollLifeCycle
private[zeromq] case object NoResults extends PollLifeCycle
private[zeromq] case object Results extends PollLifeCycle
private[zeromq] case object Closing extends PollLifeCycle

private[zeromq] class ConcurrentSocketActor(params: SocketParameters) extends Actor {

  private val noBytes = Array[Byte]()
  private val socket: Socket = params.context.socket(params.socketType)
  private val poller: Poller = params.context.poller

  override def receive: Receive = {
    case Send(frames) ⇒
      sendFrames(frames)
      pollAndReceiveFrames()
    case ZMQMessage(frames) ⇒
      sendFrames(frames)
      pollAndReceiveFrames()
    case Connect(endpoint) ⇒
      socket.connect(endpoint)
      notifyListener(Connecting)
      pollAndReceiveFrames()
    case Bind(endpoint) ⇒
      socket.bind(endpoint)
      pollAndReceiveFrames()
    case Subscribe(topic) ⇒
      socket.subscribe(topic.toArray)
      pollAndReceiveFrames()
    case Unsubscribe(topic) ⇒
      socket.unsubscribe(topic.toArray)
      pollAndReceiveFrames()
    case Linger(value) ⇒
      socket.setLinger(value)
    case Linger ⇒
      sender ! socket.getLinger
    case ReconnectIVL ⇒
      sender ! socket.getReconnectIVL
    case ReconnectIVL(value) ⇒
      socket.setReconnectIVL(value)
    case Backlog ⇒
      sender ! socket.getBacklog
    case Backlog(value) ⇒
      socket.setBacklog(value)
    case ReconnectIVLMax ⇒
      sender ! socket.getReconnectIVLMax
    case ReconnectIVLMax(value) ⇒
      socket.setReconnectIVLMax(value)
    case MaxMsgSize ⇒
      sender ! socket.getMaxMsgSize
    case MaxMsgSize(value) ⇒
      socket.setMaxMsgSize(value)
    case SndHWM ⇒
      sender ! socket.getSndHWM
    case SndHWM(value) ⇒
      socket.setSndHWM(value)
    case RcvHWM ⇒
      sender ! socket.getRcvHWM
    case RcvHWM(value) ⇒
      socket.setRcvHWM(value)
    case HWM(value) ⇒
      socket.setHWM(value)
    case Swap ⇒
      sender ! socket.getSwap
    case Swap(value) ⇒
      socket.setSwap(value)
    case Affinity ⇒
      sender ! socket.getAffinity
    case Affinity(value) ⇒
      socket.setAffinity(value)
    case Identity ⇒
      sender ! socket.getIdentity
    case Identity(value) ⇒
      socket.setIdentity(value)
    case Rate ⇒
      sender ! socket.getRate
    case Rate(value) ⇒
      socket.setRate(value)
    case RecoveryInterval ⇒
      sender ! socket.getRecoveryInterval
    case RecoveryInterval(value) ⇒
      socket.setRecoveryInterval(value)
    case MulticastLoop ⇒
      sender ! socket.hasMulticastLoop
    case MulticastLoop(value) ⇒
      socket.setMulticastLoop(value)
    case MulticastHops ⇒
      sender ! socket.getMulticastHops
    case MulticastHops(value) ⇒
      socket.setMulticastHops(value)
    case ReceiveTimeOut ⇒
      sender ! socket.getReceiveTimeOut
    case ReceiveTimeOut(value) ⇒
      socket.setReceiveTimeOut(value)
    case SendTimeOut ⇒
      sender ! socket.getSendTimeOut
    case SendTimeOut(value) ⇒
      socket.setSendTimeOut(value)
    case SendBufferSize ⇒
      sender ! socket.getSendBufferSize
    case SendBufferSize(value) ⇒
      socket.setSendBufferSize(value)
    case ReceiveBufferSize ⇒
      sender ! socket.getReceiveBufferSize
    case ReceiveBufferSize(value) ⇒
      socket.setReceiveBufferSize(value)
    case ReceiveMore ⇒
      sender ! socket.hasReceiveMore
    case FileDescriptor ⇒
      sender ! socket.getFD
    case 'poll ⇒ {
      currentPoll = None
      pollAndReceiveFrames()
    }
    case 'receiveFrames ⇒ {
      receiveFrames() match {
        case Seq()  ⇒
        case frames ⇒ notifyListener(params.deserializer(frames))
      }
      self ! 'poll
    }
  }

  override def preStart {
    poller.register(socket, Poller.POLLIN)
  }

  override def postStop {
    currentPoll foreach { _ complete Right(Closing) }
    poller.unregister(socket)
    if (socket != null) socket.close
    notifyListener(Closed)
  }

  private def sendFrames(frames: Seq[Frame]) {
    def sendBytes(bytes: Seq[Byte], flags: Int) {
      socket.send(bytes.toArray, flags)
    }
    val iter = frames.iterator
    while (iter.hasNext) {
      val payload = iter.next.payload
      val flags = if (iter.hasNext) JZMQ.SNDMORE else 0
      sendBytes(payload, flags)
    }
  }

  private var currentPoll: Option[Promise[PollLifeCycle]] = None
  private def pollAndReceiveFrames() {
    currentPoll = currentPoll orElse newEventLoop
  }

  private def newEventLoop: Option[Promise[PollLifeCycle]] = if (poller.getSize > 0) {
    implicit val executor = context.system.dispatchers.defaultGlobalDispatcher
    Some((Future {
      if (poller.poll(params.pollTimeoutDuration.toMicros) > 0 && poller.pollin(0)) Results else NoResults
    }).asInstanceOf[Promise[PollLifeCycle]] onSuccess {
      case Results   ⇒ if (!self.isTerminated) self ! 'receiveFrames
      case NoResults ⇒ if (!self.isTerminated) self ! 'poll
      case _         ⇒ currentPoll = None
    } onFailure {
      case ex ⇒ {
        if (context.system != null) {
          context.system.log.error(ex, "There was an error receiving messages on the zeromq socket")
        }
        if (!self.isTerminated) self ! 'poll
      }
    })
  } else None

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
