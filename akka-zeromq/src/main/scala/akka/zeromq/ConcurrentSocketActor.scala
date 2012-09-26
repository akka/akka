/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.ZMQ.{ Socket, Poller }
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor._
import scala.concurrent.util.Duration
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import akka.event.Logging

private[zeromq] object ConcurrentSocketActor {
  private sealed trait PollMsg
  private case object Poll extends PollMsg
  private case object NoPoll extends PollMsg
  private case object ReqRepPoll extends PollMsg
  private case object Flush

  private class NoSocketHandleException() extends Exception("Couldn't create a zeromq socket.")

  private val DefaultContext = Context()
}

private[zeromq] class ConcurrentSocketActor(params: Seq[SocketOption]) extends Actor {

  import ConcurrentSocketActor._
  private val noBytes = Array[Byte]()
  private val zmqContext = params collectFirst { case c: Context ⇒ c } getOrElse DefaultContext

  private var deserializer = deserializerFromParams
  private val socketType = {
    import SocketType.{ ZMQSocketType ⇒ ST }
    params.collectFirst { case t: ST ⇒ t }.getOrElse(throw new IllegalArgumentException("A socket type is required"))
  }

  private val socket: Socket = zmqContext.socket(socketType)
  private val log = Logging(context.system, this)

  import SocketType._

  private val pollingMode = socketType match {
    case Pub | Push                          ⇒ NoPoll
    case Sub | Pull | Pair | Dealer | Router ⇒ Poll
    case Req | Rep                           ⇒ ReqRepPoll
  }

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
        if (flushNow) {
          flush()
        }
      }
    case opt: SocketOption    ⇒ handleSocketOption(opt)
    case q: SocketOptionQuery ⇒ handleSocketOptionQuery(q)
  }

  private def handleConnectOption(msg: SocketConnectOption): Unit = msg match {
    case Connect(endpoint) ⇒ socket.connect(endpoint); notifyListener(Connecting)
    case Bind(endpoint)    ⇒ socket.bind(endpoint); notifyListener(Binding)
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
    case ReceiveTimeout(duration)    ⇒ socket.setReceiveTimeOut(duration.toMillis.toInt)
    case SendTimeout(duration)       ⇒ socket.setSendTimeOut(duration.toMillis.toInt)
    case d: Deserializer             ⇒ deserializer = d
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
    setupTimeout()
    setupConnection()

    pollingMode match {
      case Poll       ⇒ self ! Poll
      case ReqRepPoll ⇒ if (socketType == Rep) self ! ReqRepPoll
      case NoPoll     ⇒ ()
    }
  }

  private def setupConnection(): Unit = {
    params filter (_.isInstanceOf[SocketConnectOption]) foreach { self ! _ }
    params filter (_.isInstanceOf[PubSubOption]) foreach { self ! _ }
  }

  private def setupTimeout(): Unit = {
    self ! ReceiveTimeout(recvTimeoutValue)
    self ! SendTimeout(sendTimeoutValue)
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
      socket.close
    }
  } finally { notifyListener(Closed) }

  @tailrec private def flushMessage(i: Seq[Frame]): Boolean =
    if (i.isEmpty) {
      if (pollingMode == ReqRepPoll) {
        doPoll(ReqRepPoll)
      }
      true
    } else {
      val head = i.head
      val tail = i.tail

      if (socket.send(head.payload.toArray, if (tail.nonEmpty) JZMQ.SNDMORE else 0))
        flushMessage(tail)
      else {
        pendingSends.prepend(i) // Reenqueue the rest of the message so the next flush takes care of it
        self ! Flush
        false
      }
    }

  @tailrec private def flush(): Unit =
    if (pendingSends.nonEmpty && flushMessage(pendingSends.remove(0))) flush() // Flush while things are going well

  private val recvTimeoutValue = {
    val ext = ZeroMQExtension(context.system)
    val fromConfig = params collectFirst { case ReceiveTimeout(duration) ⇒ duration }
    fromConfig getOrElse ext.DefaultRecvTimeout
  }

  private val sendTimeoutValue = {
    val ext = ZeroMQExtension(context.system)
    val fromConfig = params collectFirst { case SendTimeout(duration) ⇒ duration }
    fromConfig getOrElse ext.DefaultSendTimeout
  }
  /**
   * Compiles the methods that will deal with polling.
   * Depending on the sign of the timeout, we either schedule a poll or send the message immediately.
   */
  private val doPollTimeoutIfNeeded = {
    val duration = recvTimeoutValue

    if (duration.toMillis > 0) {
      (msg: PollMsg) ⇒ self ! msg
    } else {
      val d = -duration
      import context.dispatcher
      (msg: PollMsg) ⇒ context.system.scheduler.scheduleOnce(d, self, msg)
    }
  }

  @tailrec private def doPoll(mode: PollMsg, togo: Int = 10): Unit = {
    if (togo <= 0) self ! mode
    else receiveMessage() match {
      case Seq() ⇒ doPollTimeoutIfNeeded(mode)
      case frames ⇒ {
        notifyListener(deserializer(frames))
        if (mode == Poll) doPoll(mode, togo - 1)
      }
    }
  }

  /**
   * Does nothing if waiting for a reply from the actor, reads the ZeroMQ socket otherwhise
   * @param currentFrames
   * @return A vector of frames read from the ZeroMQ socket if any, en empty vector otherwhise.
   */
  @tailrec private def receiveMessage(currentFrames: Vector[Frame] = Vector.empty): Seq[Frame] =
    socket.recv(0) match {
      case null ⇒ /*EAGAIN*/
        if (currentFrames.isEmpty) currentFrames else receiveMessage(currentFrames)
      case bytes ⇒
        val frames = currentFrames :+ Frame(if (bytes.length == 0) noBytes else bytes)
        if (socket.hasReceiveMore) receiveMessage(frames) else frames
    }

  private val listenerOpt = params collectFirst { case Listener(l) ⇒ l }
  private def watchListener(): Unit = listenerOpt foreach context.watch
  private def notifyListener(message: Any): Unit = listenerOpt foreach { _ ! message }
}
