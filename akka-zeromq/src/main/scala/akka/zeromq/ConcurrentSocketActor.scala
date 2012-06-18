/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import akka.dispatch.{ Promise, Future }
import akka.event.Logging
import annotation.tailrec
import java.util.concurrent.TimeUnit
import collection.mutable.ListBuffer
import akka.util.{ NonFatal, Duration }

private[zeromq] object ConcurrentSocketActor {
  private sealed trait PollMsg
  private case object Poll extends PollMsg
  private case object PollCareful extends PollMsg

  private case object Flush

  private class NoSocketHandleException() extends Exception("Couldn't create a zeromq socket.")

  private val DefaultContext = Context()
}
private[zeromq] class ConcurrentSocketActor(params: Seq[SocketOption]) extends Actor {

  import ConcurrentSocketActor._
  private val noBytes = Array[Byte]()
  private val zmqContext = params collectFirst { case c: Context ⇒ c } getOrElse DefaultContext

  private val deserializer = deserializerFromParams
  private val socketType = {
    import SocketType.{ ZMQSocketType ⇒ ST }
    params.collectFirst { case t: ST ⇒ t }.getOrElse(throw new IllegalArgumentException("A socket type is required"))
  }

  private val socket: Socket = zmqContext.socket(socketType)
  private val poller: Poller = zmqContext.poller
  private val log = Logging(context.system, this)

  private val pendingSends = new ListBuffer[Seq[Frame]]

  def receive = {
    case m: PollMsg         ⇒ doPoll(m)
    case ZMQMessage(frames) ⇒ handleRequest(Send(frames))
    case r: Request         ⇒ handleRequest(r)
    case Flush              ⇒ flush()
    case Terminated(_)      ⇒ context stop self
  }

  private def handleRequest(msg: Request): Unit = msg match {
    case Send(frames) ⇒
      if (frames.nonEmpty) {
        val flushNow = pendingSends.isEmpty
        pendingSends.append(frames)
        if (flushNow) flush()
      }
    case opt: SocketOption    ⇒ handleSocketOption(opt)
    case q: SocketOptionQuery ⇒ handleSocketOptionQuery(q)
  }

  private def handleConnectOption(msg: SocketConnectOption): Unit = msg match {
    case Connect(endpoint) ⇒ socket.connect(endpoint); notifyListener(Connecting)
    case Bind(endpoint)    ⇒ socket.bind(endpoint)
  }

  private def handlePubSubOption(msg: PubSubOption): Unit = msg match {
    case Subscribe(topic)   ⇒ socket.subscribe(topic.toArray)
    case Unsubscribe(topic) ⇒ socket.unsubscribe(topic.toArray)
  }

  private def handleSocketOption(msg: SocketOption): Unit = msg match {
    case x: SocketMeta               ⇒ throw new IllegalStateException("SocketMeta " + x + " only allowed for setting up a socket")
    case c: SocketConnectOption      ⇒ handleConnectOption(c)
    case ps: PubSubOption            ⇒ handlePubSubOption(ps)
    case Linger(value)               ⇒ socket.setLinger(value)
    case ReconnectIVL(value)         ⇒ socket.setReconnectIVL(value)
    case Backlog(value)              ⇒ socket.setBacklog(value)
    case ReconnectIVLMax(value)      ⇒ socket.setReconnectIVLMax(value)
    case MaxMsgSize(value)           ⇒ socket.setMaxMsgSize(value)
    case SendHighWatermark(value)    ⇒ socket.setSndHWM(value)
    case ReceiveHighWatermark(value) ⇒ socket.setRcvHWM(value)
    case HighWatermark(value)        ⇒ socket.setHWM(value)
    case Swap(value)                 ⇒ socket.setSwap(value)
    case Affinity(value)             ⇒ socket.setAffinity(value)
    case Identity(value)             ⇒ socket.setIdentity(value)
    case Rate(value)                 ⇒ socket.setRate(value)
    case RecoveryInterval(value)     ⇒ socket.setRecoveryInterval(value)
    case MulticastLoop(value)        ⇒ socket.setMulticastLoop(value)
    case MulticastHops(value)        ⇒ socket.setMulticastHops(value)
    case SendBufferSize(value)       ⇒ socket.setSendBufferSize(value)
    case ReceiveBufferSize(value)    ⇒ socket.setReceiveBufferSize(value)
  }

  private def handleSocketOptionQuery(msg: SocketOptionQuery): Unit =
    sender ! (msg match {
      case Linger               ⇒ socket.getLinger
      case ReconnectIVL         ⇒ socket.getReconnectIVL
      case Backlog              ⇒ socket.getBacklog
      case ReconnectIVLMax      ⇒ socket.getReconnectIVLMax
      case MaxMsgSize           ⇒ socket.getMaxMsgSize
      case SendHighWatermark    ⇒ socket.getSndHWM
      case ReceiveHighWatermark ⇒ socket.getRcvHWM
      case Swap                 ⇒ socket.getSwap
      case Affinity             ⇒ socket.getAffinity
      case Identity             ⇒ socket.getIdentity
      case Rate                 ⇒ socket.getRate
      case RecoveryInterval     ⇒ socket.getRecoveryInterval
      case MulticastLoop        ⇒ socket.hasMulticastLoop
      case MulticastHops        ⇒ socket.getMulticastHops
      case SendBufferSize       ⇒ socket.getSendBufferSize
      case ReceiveBufferSize    ⇒ socket.getReceiveBufferSize
      case FileDescriptor       ⇒ socket.getFD
    })

  override def preStart {
    watchListener()
    setupSocket()
    poller.register(socket, Poller.POLLIN)
    setupConnection()

    import SocketType._
    socketType match {
      case Pub | Push                          ⇒ // don’t poll
      case Sub | Pull | Pair | Dealer | Router ⇒ self ! Poll
      case Req | Rep                           ⇒ self ! PollCareful
    }
  }

  private def setupConnection(): Unit = {
    params filter (_.isInstanceOf[SocketConnectOption]) foreach { self ! _ }
    params filter (_.isInstanceOf[PubSubOption]) foreach { self ! _ }
  }

  private def deserializerFromParams: Deserializer =
    params collectFirst { case d: Deserializer ⇒ d } getOrElse new ZMQMessageDeserializer

  private def setupSocket() = params foreach {
    case _: SocketConnectOption | _: PubSubOption | _: SocketMeta ⇒ // ignore, handled differently
    case m ⇒ self ! m
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = context.children foreach context.stop //Do not call postStop

  override def postRestart(reason: Throwable): Unit = () // Do nothing

  override def postStop: Unit = try {
    if (socket != null) {
      poller.unregister(socket)
      socket.close
    }
  } finally notifyListener(Closed)

  @tailrec private def flushMessage(i: Seq[Frame]): Boolean =
    if (i.isEmpty)
      true
    else {
      val head = i.head
      val tail = i.tail
      if (socket.send(head.payload.toArray, if (tail.nonEmpty) JZMQ.SNDMORE else 0)) flushMessage(tail)
      else {
        pendingSends.prepend(i) // Reenqueue the rest of the message so the next flush takes care of it
        self ! Flush
        false
      }
    }

  @tailrec private def flush(): Unit =
    if (pendingSends.nonEmpty && flushMessage(pendingSends.remove(0))) flush() // Flush while things are going well

  // this is a “PollMsg=>Unit” which either polls or schedules Poll, depending on the sign of the timeout
  private val doPollTimeout = {
    val fromConfig = params collectFirst { case PollTimeoutDuration(duration) ⇒ duration }
    val duration = fromConfig getOrElse ZeroMQExtension(context.system).DefaultPollTimeout
    if (duration > Duration.Zero) { (msg: PollMsg) ⇒
      // for positive timeout values, do poll (i.e. block this thread)
      poller.poll(duration.toMicros)
      self ! msg
    } else {
      val d = -duration

      { (msg: PollMsg) ⇒
        // for negative timeout values, schedule Poll token -duration into the future
        context.system.scheduler.scheduleOnce(d, self, msg)
        ()
      }
    }
  }

  @tailrec private def doPoll(mode: PollMsg, togo: Int = 10): Unit =
    receiveMessage(mode) match {
      case null  ⇒ // receiveMessage has already done something special here
      case Seq() ⇒ doPollTimeout(mode)
      case frames ⇒
        notifyListener(deserializer(frames))
        if (togo > 0) doPoll(mode, togo - 1)
        else self ! mode
    }

  @tailrec private def receiveMessage(mode: PollMsg, currentFrames: Vector[Frame] = Vector.empty): Seq[Frame] = {
    val result = mode match {
      case Poll        ⇒ socket.recv(JZMQ.NOBLOCK)
      case PollCareful ⇒ if (poller.poll(0) > 0) socket.recv(0) else null
    }
    result match {
      case null ⇒
        if (currentFrames.isEmpty) currentFrames
        else throw new IllegalStateException("no more frames available while socket.hasReceivedMore==true")
      case bytes ⇒
        val frames = currentFrames :+ Frame(if (bytes.length == 0) noBytes else bytes)
        if (socket.hasReceiveMore) receiveMessage(mode, frames) else frames
    }
  }

  private val listenerOpt = params collectFirst { case Listener(l) ⇒ l }
  private def watchListener(): Unit = listenerOpt foreach context.watch
  private def notifyListener(message: Any): Unit = listenerOpt foreach { _ ! message }
}
