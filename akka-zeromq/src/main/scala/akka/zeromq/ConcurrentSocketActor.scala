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
import annotation.tailrec
import akka.util.Duration
import java.util.concurrent.TimeUnit

private[zeromq] sealed trait PollLifeCycle
private[zeromq] case object NoResults extends PollLifeCycle
private[zeromq] case object Results extends PollLifeCycle
private[zeromq] case object Closing extends PollLifeCycle

private[zeromq] object ConcurrentSocketActor {
  private case object Poll
  private case object ReceiveFrames
  private case object ClearPoll
  private case class PollError(ex: Throwable)

  private class NoSocketHandleException() extends Exception("Couldn't create a zeromq socket.")

  private val DefaultContext = Context()
}
private[zeromq] class ConcurrentSocketActor(params: Seq[SocketOption]) extends Actor {

  import ConcurrentSocketActor._
  private val noBytes = Array[Byte]()
  private val zmqContext = {
    params collectFirst { case c: Context ⇒ c } getOrElse DefaultContext
  }

  private val deserializer = deserializerFromParams
  private val socket: Socket = socketFromParams
  private val poller: Poller = zmqContext.poller
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
    case Terminated(_) ⇒ context stop self
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
    case SendBufferSize           ⇒ sender ! socket.getSendBufferSize
    case ReceiveBufferSize        ⇒ sender ! socket.getReceiveBufferSize
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
    watchListener()
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
    (params collectFirst { case t: SocketType.ZMQSocketType ⇒ zmqContext.socket(t) } get)
  }

  private def deserializerFromParams = {
    params collectFirst { case d: Deserializer ⇒ d } getOrElse new ZMQMessageDeserializer
  }

  private def setupSocket() = {
    params foreach {
      case _: SocketConnectOption | _: PubSubOption | _: SocketMeta ⇒ // ignore, handled differently
      case m ⇒ self ! m
    }
  }

  override def postStop {
    try {
      currentPoll foreach { _ complete Right(Closing) }
      poller.unregister(socket)
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

  private val eventLoopDispatcher = {
    val fromConfig = params collectFirst { case PollDispatcher(name) ⇒ context.system.dispatchers.lookup(name) }
    fromConfig getOrElse context.system.dispatcher
  }

  private val defaultPollTimeout =
    Duration(context.system.settings.config.getMilliseconds("akka.zeromq.poll-timeout"), TimeUnit.MILLISECONDS)

  private val pollTimeout = {
    val fromConfig = params collectFirst { case PollTimeoutDuration(duration) ⇒ duration }
    fromConfig getOrElse defaultPollTimeout
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
    @tailrec def receiveBytes(next: Array[Byte], currentFrames: Vector[Frame] = Vector.empty): Seq[Frame] = {
      val nwBytes = if (next != null && next.nonEmpty) next else noBytes
      val frames = currentFrames :+ Frame(nwBytes)
      if (socket.hasReceiveMore) receiveBytes(socket.recv(0), frames) else frames
    }

    receiveBytes(socket.recv(0))
  }

  private val listenerOpt = params collectFirst { case Listener(l) ⇒ l }
  private def watchListener() {
    listenerOpt foreach context.watch
  }

  private def notifyListener(message: Any) {
    listenerOpt foreach { _ ! message }
  }
}
