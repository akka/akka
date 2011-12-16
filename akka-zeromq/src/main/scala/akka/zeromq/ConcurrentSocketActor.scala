/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{Socket, Poller}
import org.zeromq.{ZMQ => JZMQ}
import akka.event.EventHandler
import akka.actor._
import akka.dispatch.{CompletableFuture, Dispatchers, MessageDispatcher, Future}

private[zeromq] sealed trait PollLifeCycle
private[zeromq] case object NoResults extends PollLifeCycle
private[zeromq] case object Results extends PollLifeCycle
private[zeromq] case object Closing extends PollLifeCycle


private[zeromq] class ConcurrentSocketActor(params: SocketParameters, dispatcher: MessageDispatcher) extends Actor {

  private val noBytes = Array[Byte]()
  private val socket: Socket = params.context.socket(params.socketType)
  private val poller: Poller = params.context.poller

  self.dispatcher = Dispatchers.newThreadBasedDispatcher(self)

  override def receive: Receive = {
    case Send(frames) =>
      sendFrames(frames)
      pollAndReceiveFrames()
    case ZMQMessage(frames) =>
      sendFrames(frames)
      pollAndReceiveFrames()
    case Connect(endpoint) =>
      socket.connect(endpoint)
      notifyListener(Connecting)
      pollAndReceiveFrames()
    case Bind(endpoint) =>
      socket.bind(endpoint)
      pollAndReceiveFrames()
    case Subscribe(topic) =>
      socket.subscribe(topic.toArray)
      pollAndReceiveFrames()
    case Unsubscribe(topic) =>
      socket.unsubscribe(topic.toArray)
      pollAndReceiveFrames()
    case Linger(value) =>
      socket.setLinger(value)
    case Linger =>
      self.reply(socket.getLinger)
    case ReconnectIVL =>
      self.reply(socket.getReconnectIVL)
    case ReconnectIVL(value) =>
      socket.setReconnectIVL(value)
    case Backlog =>
      self.reply(socket.getBacklog)
    case Backlog(value) =>
      socket.setBacklog(value)
    case ReconnectIVLMax =>
      self.reply(socket.getReconnectIVLMax)
    case ReconnectIVLMax(value) =>
      socket.setReconnectIVLMax(value)
    case MaxMsgSize =>
      self.reply(socket.getMaxMsgSize)
    case MaxMsgSize(value) =>
      socket.setMaxMsgSize(value)
    case SndHWM =>
      self.reply(socket.getSndHWM)
    case SndHWM(value) =>
      socket.setSndHWM(value)
    case RcvHWM =>
      self.reply(socket.getRcvHWM)
    case RcvHWM(value) =>
      socket.setRcvHWM(value)
    case HWM(value) =>
      socket.setHWM(value)
    case Swap =>
      self.reply(socket.getSwap)
    case Swap(value) =>
      socket.setSwap(value)
    case Affinity =>
      self.reply(socket.getAffinity)
    case Affinity(value) =>
      socket.setAffinity(value)
    case Identity =>
      self.reply(socket.getIdentity)
    case Identity(value) =>
      socket.setIdentity(value)
    case Rate =>
      self.reply(socket.getRate)
    case Rate(value) =>
      socket.setRate(value)
    case RecoveryInterval =>
      self.reply(socket.getRecoveryInterval)
    case RecoveryInterval(value) =>
      socket.setRecoveryInterval(value)
    case MulticastLoop =>
      self.reply(socket.hasMulticastLoop)
    case MulticastLoop(value) =>
      socket.setMulticastLoop(value)
    case MulticastHops =>
      self.reply(socket.getMulticastHops)
    case MulticastHops(value) =>
      socket.setMulticastHops(value)
    case ReceiveTimeOut =>
      self.reply(socket.getReceiveTimeOut)
    case ReceiveTimeOut(value) =>
      socket.setReceiveTimeOut(value)
    case SendTimeOut =>
      self.reply(socket.getSendTimeOut)
    case SendTimeOut(value) =>
      socket.setSendTimeOut(value)
    case SendBufferSize =>
      self.reply(socket.getSendBufferSize)
    case SendBufferSize(value) =>
      socket.setSendBufferSize(value)
    case ReceiveBufferSize =>
      self.reply(socket.getReceiveBufferSize)
    case ReceiveBufferSize(value) =>
      socket.setReceiveBufferSize(value)
    case ReceiveMore =>
      self.reply(socket.hasReceiveMore)
    case FileDescriptor =>
      self.reply(socket.getFD)
    case 'poll => {
      currentPoll = None
      pollAndReceiveFrames()
    }
    case 'receiveFrames => {
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
    currentPoll foreach { _ completeWithResult Closing }
    poller.unregister(socket)
    socket.close
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
  
  private var currentPoll: Option[CompletableFuture[PollLifeCycle]] = None
  private def pollAndReceiveFrames() {
    currentPoll = currentPoll orElse Some(newEventLoop.asInstanceOf[CompletableFuture[PollLifeCycle]])
  }

  private def newEventLoop = {
    Future({
      if(poller.poll(params.pollTimeoutDuration.toMillis) > 0 && poller.pollin(0)) Results else NoResults
    }, params.pollTimeoutDuration.toMillis * 2)(dispatcher) onResult {
      case Results => if (self.isRunning) self ! 'receiveFrames
      case NoResults => if (self.isRunning) self ! 'poll
      case _ => currentPoll = None
    } onException {
      case ex ⇒ {
        EventHandler.error(ex, this, "There was an error receiving messages on the zeromq socket")
        if (self.isRunning) self ! 'poll
      }
    }
  }
  
  private def receiveFrames(): Seq[Frame] = {
    
    @inline def receiveBytes(): Array[Byte] = socket.recv(0) match {
      case null => noBytes
      case bytes: Array[Byte] if bytes.length > 0 => bytes
      case _ => noBytes
    }

    receiveBytes() match {
      case `noBytes` => Vector.empty
      case someBytes =>
        var frames = Vector(Frame(someBytes))
        while (socket.hasReceiveMore) receiveBytes() match {
          case `noBytes` =>
          case someBytes => frames :+= Frame(someBytes)
        }
        frames
    }
  }
  
  private def notifyListener(message: Any) {
    params.listener.foreach { listener =>
      if (listener.isShutdown)
        self.stop
      else
        listener ! message
    }
  }
}
