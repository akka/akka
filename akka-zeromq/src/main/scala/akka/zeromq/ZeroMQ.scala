/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.{Actor, ActorRef}
import akka.dispatch.{Dispatchers, MessageDispatcher}
import akka.zeromq.SocketType._
import akka.util.Duration
import akka.util.duration._
import java.nio.charset.Charset

case class SocketParameters(
  context: Context,
  socketType: SocketType,
  listener: Option[ActorRef] = None,
  deserializer: Deserializer = new ZMQMessageDeserializer,
  pollTimeoutDuration: Duration = 100 millis,
  options: Seq[SocketOption] = Seq.empty)

/**
 * A base trait for all possible socket options
 */
trait SocketOption {
  type OptionType
  def value: OptionType
}

/**
 * A socket option with an int value
 */
trait IntSocketOption extends SocketOption { type OptionType = Int }

/**
 * A socket option with a long value
 */
trait LongSocketOption extends SocketOption { type OptionType = Long }

/**
 * A socket option with a string value
 */
trait StringSocketOption extends SocketOption { type OptionType = String }

/**
 * A socket option with a boolean value
 */
trait BoolSocketOption extends SocketOption { type OptionType = Boolean }

abstract class LingerOption(val value: Long) extends LongSocketOption
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
case class Linger(override val value: Long) extends LingerOption(value)

/**
 * Set the linger to 0, doesn't block and discards messages that haven't been sent yet.
 */
case object NoLinger extends LingerOption(0)

/**
 * Configure the high watermark on this socket.
 * The high water mark is a hard limit on the maximum number of outstanding messages ØMQ shall queue in memory
 * for any single peer that the specified socket is communicating with.
 * If this limit has been reached the socket shall enter an exceptional state and depending on the socket type,
 * ØMQ shall take appropriate action such as blocking or dropping sent messages.
 *
 * The default value of the HWM is no limit.
 *
 * @param value The amount of buffered messages
 */
case class HWM(value: Long) extends LongSocketOption

/**
 * The Affinity option shall set the I/O thread affinity for newly created connections on the specified socket.
 *
 * Affinity determines which threads from the ØMQ I/O thread pool associated with the socket's context shall
 * handle newly created connections. A value of zero specifies no affinity, meaning that work shall be distributed
 * fairly among all ØMQ I/O threads in the thread pool.
 * For non-zero values, the lowest bit corresponds to thread 1, second lowest bit to thread 2 and so on.
 * For example, a value of 3 specifies that subsequent connections on socket shall be handled exclusively by I/O threads 1 and 2.
 *
 * @param value The bitmap for the I/O thread affinity
 */
case class Affinity(value: Long) extends LongSocketOption

/**
 * Sets the maximum send or receive data rate for multicast transports such as pgm using the specified socket.
 *
 * @param value The kilobits per second
 */
case class Rate(value: Long) extends LongSocketOption

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
case class RecoveryIVL(value: Long) extends LongSocketOption

/**
 * Sets the underlying kernel transmit buffer size for the socket to the specified size in bytes. A value of zero means
 * leave the OS default unchanged.
 * For details please refer to your operating system documentation for the SO_SNDBUF socket option.
 *
 * @param value The amount of bytes for the buffer
 */
case class SndBuf(value: Long) extends LongSocketOption

/**
 * Sets the underlying kernel receive buffer size for the socket to the specified size in bytes. A value of zero means
 * leave the OS default unchanged.
 * For details refer to your operating system documentation for the SO_RCVBUF socket option.
 *
 * @param value The amount of bytes for the buffer
 */
case class RcvBuf(value: Long) extends LongSocketOption

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
case class Identity(value: String) extends StringSocketOption {
  private val Utf8 = Charset.forName("UTF-8")
  require(value.nonEmpty, "The identity shouldn't be empty")
  require(value.getBytes(Utf8).size < 255, "The identity shouldn't be longer than 255 bytes")
}

/**
 * Controls whether data sent via multicast transports using the specified socket can also be received by the sending
 * host via loop-back. A value of zero disables the loop-back functionality, while the default value of 1 enables the
 * loop-back functionality. Leaving multicast loop-back enabled when it is not required can have a negative impact
 * on performance. Where possible, disable McastLoop in production environments.
 *
 * @param value Flag indicating whether or not loopback multicast is enabled
 */
case class McastLoop(value: Boolean) extends BoolSocketOption

object ZeroMQ {
  def newContext(numIoThreads: Int = 1) = {
    new Context(numIoThreads)
  }
  def newSocket(params: SocketParameters, supervisor: Option[ActorRef] = None, dispatcher: MessageDispatcher = Dispatchers.defaultGlobalDispatcher) = {
    val socket = Actor.actorOf(new ConcurrentSocketActor(params, dispatcher))
    supervisor.foreach(_.link(socket))
    socket.start
  }
}
