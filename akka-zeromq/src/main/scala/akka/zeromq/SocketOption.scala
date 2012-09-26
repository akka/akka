/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import com.google.protobuf.Message
import akka.actor.ActorRef
import scala.concurrent.duration._
import scala.collection.immutable
import org.zeromq.{ ZMQ ⇒ JZMQ }
import org.zeromq.ZMQ.{ Poller, Socket }

/**
 * Marker trait representing request messages for zeromq
 */
sealed trait Request

/**
 * Marker trait representing the base for all socket options
 */
sealed trait SocketOption extends Request

/**
 * Marker trait representing the base for all meta operations for a socket
 * such as the context, listener, socket type and poller dispatcher
 */
sealed trait SocketMeta extends SocketOption

/**
 * A base trait for connection options for a ZeroMQ socket
 */
sealed trait SocketConnectOption extends SocketOption {
  def endpoint: String
}

/**
 * A base trait for timeout values for a ZeroMQ socket
 */
sealed trait SocketTimeoutValue extends SocketOption {

}
/**
 * A base trait for pubsub options for the ZeroMQ socket
 */
sealed trait PubSubOption extends SocketOption {
  def payload: immutable.Seq[Byte]
}

/**
 * A marker trait to group option queries together
 */
sealed trait SocketOptionQuery extends Request

/**
 * This socket should be a client socket and connect to the specified endpoint
 *
 * @param endpoint URI (ex. tcp://127.0.0.1:5432)
 */
case class Connect(endpoint: String) extends SocketConnectOption

/**
 * Start listening with this server socket on the specified address
 *
 * @param endpoint
 */
case class Bind(endpoint: String) extends SocketConnectOption

/**
 * Companion object for a ZeroMQ I/O thread pool
 */
object Context {
  def apply(numIoThreads: Int = 1): Context = new Context(numIoThreads)
}

/**
 * Represents an I/O thread pool for ZeroMQ sockets.
 * By default the ZeroMQ module uses an I/O thread pool with 1 thread.
 * For most applications that should be sufficient
 *
 * @param numIoThreads
 */
class Context(numIoThreads: Int) extends SocketMeta {
  private val context = JZMQ.context(numIoThreads)

  def socket(socketType: SocketType.ZMQSocketType): Socket = context.socket(socketType.id)

  def poller: Poller = context.poller

  def term: Unit = context.term
}

/**
 * A base trait for message deserializers
 */
trait Deserializer extends SocketOption {
  def apply(frames: immutable.Seq[Frame]): Any
}

/**
 * The different socket types you can create with zeromq
 */
object SocketType {

  abstract class ZMQSocketType(val id: Int) extends SocketMeta

  /**
   * A Publisher socket
   */
  object Pub extends ZMQSocketType(JZMQ.PUB)

  /**
   * A subscriber socket
   */
  object Sub extends ZMQSocketType(JZMQ.SUB)

  /**
   * A dealer socket
   */
  object Dealer extends ZMQSocketType(JZMQ.DEALER)

  /**
   * A router socket
   */
  object Router extends ZMQSocketType(JZMQ.ROUTER)

  /**
   * A request socket
   */
  object Req extends ZMQSocketType(JZMQ.REQ)

  /**
   * A reply socket
   */
  object Rep extends ZMQSocketType(JZMQ.REP)

  /**
   * A push socket
   */
  object Push extends ZMQSocketType(JZMQ.PUSH)

  /**
   * A pull socket
   */
  object Pull extends ZMQSocketType(JZMQ.PULL)

  /**
   * A Pair socket
   */
  object Pair extends ZMQSocketType(JZMQ.PAIR)
}

/**
 * An option containing the listener for the socket
 * @param listener
 */
case class Listener(listener: ActorRef) extends SocketMeta

/**
 * The [[akka.zeromq.Subscribe]] option establishes a new message filter on a [[akka.zeromq.SocketType.Pub]] socket.
 * Newly created [[akka.zeromq.SocketType.Sub]] sockets filter out all incoming messages,
 * therefore you should send this option to establish an initial message filter.
 *
 * An empty payload of length zero will subscribe to all incoming messages.
 * A non-empty payload will subscribe to all messages beginning with the specified prefix.
 * Multiple filters may be attached to a single [[akka.zeromq.SocketType.Sub]] socket,
 * in which case a message will be accepted if it matches at least one filter.
 *
 * @param payload the topic to subscribe to
 */
case class Subscribe(payload: immutable.Seq[Byte]) extends PubSubOption {
  def this(topic: String) = this(topic.getBytes("UTF-8").to[immutable.Seq])
}
object Subscribe {
  def apply(topic: String): Subscribe = new Subscribe(topic)
  val all = Subscribe("")
}

/**
 * The [[akka.zeromq.Unsubscribe]] option shall remove an existing message filter
 * on a [[akka.zeromq.SocketType.Sub]] socket. The filter specified must match an existing filter
 * previously established with the [[akka.zeromq.Subscribe]] option. If the socket has several instances of the
 * same filter attached the [[akka.zeromq.Unsubscribe]] option shall remove only one instance, leaving the rest
 * in place and functional.
 *
 * @param payload
 */
case class Unsubscribe(payload: immutable.Seq[Byte]) extends PubSubOption {
  def this(topic: String) = this(topic.getBytes("UTF-8").to[immutable.Seq])
}
object Unsubscribe {
  def apply(topic: String): Unsubscribe = new Unsubscribe(topic)
}

/**
 * Send a message over the zeromq socket
 * @param frames
 */
case class Send(frames: immutable.Seq[Frame]) extends Request

/**
 * A message received over the zeromq socket
 * @param frames
 */
case class ZMQMessage(frames: immutable.Seq[Frame]) {

  def this(frame: Frame) = this(List(frame))
  def this(frame1: Frame, frame2: Frame) = this(List(frame1, frame2))
  def this(frameArray: Array[Frame]) = this(frameArray.to[immutable.Seq])

  /**
   * Convert the bytes in the first frame to a String, using specified charset.
   */
  def firstFrameAsString(charsetName: String): String = new String(frames.head.payload.toArray, charsetName)
  /**
   * Convert the bytes in the first frame to a String, using "UTF-8" charset.
   */
  def firstFrameAsString: String = firstFrameAsString("UTF-8")

  def payload(frameIndex: Int): Array[Byte] = frames(frameIndex).payload.toArray
}
object ZMQMessage {
  def apply(bytes: Array[Byte]): ZMQMessage = new ZMQMessage(List(Frame(bytes)))
  def apply(frames: Frame*): ZMQMessage = new ZMQMessage(frames.to[immutable.Seq])
  def apply(message: Message): ZMQMessage = apply(message.toByteArray)
}

/**
 * Configure this socket to have a linger of the specified value
 *
 * The linger period determines how long pending messages which have yet to be sent to a peer shall linger
 * in memory after a socket is closed, and further affects the termination of the socket's context.
 *
 * The following outlines the different behaviours:
 * <ul>
 *   <li>The default value of -1 specifies an infinite linger period.
 *     Pending messages shall not be discarded after the socket is closed;
 *     attempting to terminate the socket's context shall block until all pending messages
 *     have been sent to a peer.</li>
 *   <li>The value of 0 specifies no linger period. Pending messages shall be discarded immediately when the socket is closed.</li>
 *   <li>Positive values specify an upper bound for the linger period in milliseconds.
 *     Pending messages shall not be discarded after the socket is closed;
 *     attempting to terminate the socket's context shall block until either all pending messages have been sent to a peer,
 *     or the linger period expires, after which any pending messages shall be discarded.</li>
 * </ul>
 *
 * @param value The value in milliseconds for the linger option
 */
case class Linger(value: Long) extends SocketOption

/**
 * Gets the linger option @see [[akka.zeromq.Linger]]
 */
object Linger extends SocketOptionQuery {
  val no: Linger = Linger(0)
}

/**
 * Sets the recovery interval for multicast transports using the specified socket.
 * The recovery interval determines the maximum time in seconds that a receiver can be absent from a multicast group
 * before unrecoverable data loss will occur.
 *
 * Exercise care when setting large recovery intervals as the data needed for recovery will be held in memory.
 * For example, a 1 minute recovery interval at a data rate of 1Gbps requires a 7GB in-memory buffer.
 *
 * @param value The interval in seconds
 */
case class ReconnectIVL(value: Long) extends SocketOption

/**
 * Gets the recover interval @see [[akka.zeromq.ReconnectIVL]]
 */
object ReconnectIVL extends SocketOptionQuery

/**
 * The [[akka.zeromq.ReconnectIVLMax]] option shall set the maximum reconnection interval for the specified socket.
 * This is the maximum period ØMQ shall wait between attempts to reconnect. On each reconnect attempt,
 * the previous interval shall be doubled untill [[akka.zeromq.ReconnectIVLMax]] is reached.
 * This allows for exponential backoff strategy. Default value means no exponential backoff is performed
 * and reconnect interval calculations are only based on [[akka.zeromq.ReconnectIVL]].
 *
 * @see [[akka.zeromq.ReconnectIVL]]
 *
 * This is a ZeroMQ 3.0 option
 *
 * @param value
 */
case class ReconnectIVLMax(value: Long) extends SocketOption
/**
 * Gets the max reconnect IVL
 * @see [[akka.zeromq.ReconnectIVLMax]]
 */
object ReconnectIVLMax extends SocketOptionQuery

/**
 * The [[akka.zeromq.Backlog]] option shall set the maximum length of the queue of outstanding peer connections
 * for the specified socket; this only applies to connection-oriented transports. For details refer to your
 * operating system documentation for the listen function.
 *
 * @param value
 */
case class Backlog(value: Long) extends SocketOption
/**
 * Gets the backlog
 * @see [[akka.zeromq.Backlog]]
 */
object Backlog extends SocketOptionQuery

/**
 * Limits the size of the inbound message.
 * If a peer sends a message larger than [[akka.zeromq.MaxMsgSize]] it is disconnected.
 * Value of -1 means no limit.
 *
 * This is a ZeroMQ 3.0 option
 *
 * @param value
 */
case class MaxMsgSize(value: Long) extends SocketOption
object MaxMsgSize extends SocketOptionQuery

/**
 * The [[akka.zeromq.SendHighWatermark]] option shall set the high water mark for outbound messages on the specified socket.
 * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ shall queue in memory
 * for any single peer that the specified socket is communicating with.
 *
 * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
 * ØMQ shall take appropriate action such as blocking or dropping sent messages.
 *
 * This is a ZeroMQ 3.0 option
 *
 * @param value
 */
case class SendHighWatermark(value: Long) extends SocketOption

/**
 * Gets the SendHWM
 * @see [[akka.zeromq.SendHighWatermark]]
 */
object SendHighWatermark extends SocketOptionQuery

/**
 * The [[akka.zeromq.ReceiveHighWatermark]] option shall set the high water mark for inbound messages on the specified socket.
 * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ shall queue
 * in memory for any single peer that the specified socket is communicating with.
 *
 * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
 * ØMQ shall take appropriate action such as blocking or dropping sent messages.
 *
 * This is a ZeroMQ 3.0 option
 *
 * @param value
 */
case class ReceiveHighWatermark(value: Long) extends SocketOption

/**
 * Gets the ReceiveHighWatermark
 * @see [[akka.zeromq.ReceiveHighWatermark]]
 */
object ReceiveHighWatermark extends SocketOptionQuery

/**
 * The [[akka.zeromq.HighWatermark]] option shall set the high water mark for the specified socket.
 * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ shall queue in memory for
 * any single peer that the specified socket is communicating with.
 *
 * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
 * ØMQ shall take appropriate action such as blocking or dropping sent messages.
 * The default [[akka.zeromq.HighWatermark]] value of zero means "no limit".
 *
 * @param value
 */
case class HighWatermark(value: Long) extends SocketOption

/**
 * The [[akka.zeromq.Swap]] option shall set the disk offload (swap) size for the specified socket.
 * A socket which has [[akka.zeromq.Swap]] set to a non-zero value may exceed its high water mark;
 * in this case outstanding messages shall be offloaded to storage on disk rather than held in memory.
 *
 * The value of [[akka.zeromq.Swap]] defines the maximum size of the swap space in bytes.
 *
 * @param value
 */
case class Swap(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.Swap]]
 *
 * @see [[akka.zeromq.Swap]]
 */
object Swap extends SocketOptionQuery

/**
 * The [[akka.zeromq.Affinity]] option shall set the I/O thread affinity for newly created connections on the specified socket.
 *
 * Affinity determines which threads from the ØMQ I/O thread pool associated with the socket's context shall handle
 * newly created connections. A value of zero specifies no affinity, meaning that work shall be distributed fairly
 * among all ØMQ I/O threads in the thread pool. For non-zero values, the lowest bit corresponds to thread 1,
 * second lowest bit to thread 2 and so on. For example, a value of 3 specifies that subsequent connections
 * on socket shall be handled exclusively by I/O threads 1 and 2.
 *
 * @param value
 */
case class Affinity(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.Affinity]] value
 */
object Affinity extends SocketOptionQuery

/**
 * Sets the identity of the specified socket. Socket identity determines if existing ØMQ infrastructure
 * (message queues, forwarding devices) shall be identified with a specific application and persist across multiple
 * runs of the application.
 *
 * If the socket has no identity, each run of an application is completely separate from other runs.
 * However, with identity set the socket shall re-use any existing ØMQ infrastructure configured by the previous run(s).
 * Thus the application may receive messages that were sent in the meantime, message queue limits shall be shared
 * with previous run(s) and so on.
 *
 * Identity should be at least one byte and at most 255 bytes long.
 * Identities starting with binary zero are reserved for use by ØMQ infrastructure.
 *
 * @param value The identity string for this socket
 */
case class Identity(value: Array[Byte]) extends SocketOption

/**
 * Gets the [[akka.zeromq.Identity]] value
 */
object Identity extends SocketOptionQuery

/**
 * Sets the maximum send or receive data rate for multicast transports such as pgm using the specified socket.
 *
 * @param value The kilobits per second
 */
case class Rate(value: Long) extends SocketOption

/**
 * Gets the send or receive rate for the socket
 */
object Rate extends SocketOptionQuery

/**
 * Sets the recovery interval for multicast transports using the specified socket.
 * The recovery interval determines the maximum time in seconds that a receiver can be absent from a multicast group
 * before unrecoverable data loss will occur.
 *
 * Exercise care when setting large recovery intervals as the data needed for recovery will be held in memory.
 * For example, a 1 minute recovery interval at a data rate of 1Gbps requires a 7GB in-memory buffer.
 *
 * @param value The interval in seconds
 */
case class RecoveryInterval(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.RecoveryInterval]]
 */
object RecoveryInterval extends SocketOptionQuery

/**
 * Controls whether data sent via multicast transports using the specified socket can also be received by the sending
 * host via loop-back. A value of zero disables the loop-back functionality, while the default value of 1 enables the
 * loop-back functionality. Leaving multicast loop-back enabled when it is not required can have a negative impact
 * on performance. Where possible, disable McastLoop in production environments.
 *
 * @param value Flag indicating whether or not loopback multicast is enabled
 */
case class MulticastLoop(value: Boolean) extends SocketOption

/**
 * Gets the [[akka.zeromq.MulticastLoop]]
 */
object MulticastLoop extends SocketOptionQuery

/**
 * Sets the time-to-live field in every multicast packet sent from this socket.
 * The default is 1 which means that the multicast packets don't leave the local network.
 *
 * This is za ZeroMQ 3.0 option
 *
 * @param value
 */
case class MulticastHops(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.MulticastHops]]
 */
object MulticastHops extends SocketOptionQuery

/**
 * The [[akka.zeromq.SendBufferSize]] option shall set the underlying kernel transmit buffer size for the socket to
 * the specified size in bytes. A value of zero means leave the OS default unchanged.
 * For details please refer to your operating system documentation for the SO_SNDBUF socket option.
 *
 * This is a ZeroMQ 2.x only option
 *
 * @param value
 */
case class SendBufferSize(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.SendBufferSize]]
 */
object SendBufferSize extends SocketOptionQuery

/**
 * The [[akka.zeromq.ReceiveBufferSize]] option shall set the underlying kernel receive buffer size for the socket to
 * the specified size in bytes. A value of zero means leave the OS default unchanged.
 * For details refer to your operating system documentation for the SO_RCVBUF socket option.
 * @param value
 */
case class ReceiveBufferSize(value: Long) extends SocketOption

/**
 * Gets the [[akka.zeromq.ReceiveBufferSize]]
 */
object ReceiveBufferSize extends SocketOptionQuery

/**
 * Gets the file descriptor associated with the ZeroMQ socket
 */
object FileDescriptor extends SocketOptionQuery

/**
 * The timeout value for the recv method in blocking mode
 * @param duration
 */
case class ReceiveTimeout(duration: Duration = 100 millis) extends SocketOption

/**
 * The timeout value for the send method in blocking mode
 * @param duration
 */
case class SendTimeout(duration: Duration = 100 millis) extends SocketOption
