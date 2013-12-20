/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.net.Socket
import akka.io.Inet._
import com.typesafe.config.Config
import scala.concurrent.duration._
import scala.collection.immutable
import scala.collection.JavaConverters._
import akka.util.{ Bytes, ByteString }
import akka.util.Helpers.Requiring
import akka.actor._
import java.lang.{ Iterable ⇒ JIterable }

/**
 * TCP Extension for Akka’s IO layer.
 *
 * <b>All contents of the `akka.io` package is marked “experimental”.</b>
 *
 * This marker signifies that APIs may still change in response to user feedback
 * through-out the 2.2 release cycle. The implementation itself is considered
 * stable and ready for production use.
 *
 * For a full description of the design and philosophy behind this IO
 * implementation please refer to <a href="http://doc.akka.io/">the Akka online documentation</a>.
 *
 * In order to open an outbound connection send a [[Tcp.Connect]] message
 * to the [[TcpExt#manager]].
 *
 * In order to start listening for inbound connetions send a [[Tcp.Bind]]
 * message to the [[TcpExt#manager]].
 *
 * The Java API for generating TCP commands is available at [[TcpMessage]].
 */
object Tcp extends ExtensionId[TcpExt] with ExtensionIdProvider {

  override def lookup = Tcp

  override def createExtension(system: ExtendedActorSystem): TcpExt = new TcpExt(system)

  /**
   * Java API: retrieve the Tcp extension for the given system.
   */
  override def get(system: ActorSystem): TcpExt = super.get(system)

  /**
   * Scala API: this object contains all applicable socket options for TCP.
   *
   * For the Java API see [[TcpSO]].
   */
  object SO extends Inet.SoForwarders {

    // general socket options

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable SO_KEEPALIVE
     *
     * For more information see [[java.net.Socket.setKeepAlive]]
     */
    case class KeepAlive(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setKeepAlive(on)
    }

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable OOBINLINE (receipt
     * of TCP urgent data) By default, this option is disabled and TCP urgent
     * data is silently discarded.
     *
     * For more information see [[java.net.Socket.setOOBInline]]
     */
    case class OOBInline(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setOOBInline(on)
    }

    // SO_LINGER is handled by the Close code

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable TCP_NODELAY
     * (disable or enable Nagle's algorithm)
     *
     * Please note, that TCP_NODELAY is enabled by default.
     *
     * For more information see [[java.net.Socket.setTcpNoDelay]]
     */
    case class TcpNoDelay(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setTcpNoDelay(on)
    }

  }

  /**
   * The common interface for [[Command]] and [[Event]].
   */
  sealed trait Message extends NoSerializationVerificationNeeded

  /// COMMANDS

  /**
   * This is the common trait for all commands understood by TCP actors.
   */
  trait Command extends Message with SelectionHandler.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  /**
   * The Connect message is sent to the TCP manager actor, which is obtained via
   * [[TcpExt#manager]]. Either the manager replies with a [[CommandFailed]]
   * or the actor handling the new connection replies with a [[Connected]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to the [[SO]] object for a list of all supported options.
   */
  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil,
                     timeout: Option[FiniteDuration] = None) extends Command

  /**
   * The Bind message is send to the TCP manager actor, which is obtained via
   * [[TcpExt#manager]] in order to bind to a listening socket. The manager
   * replies either with a [[CommandFailed]] or the actor handling the listen
   * socket replies with a [[Bound]] message. If the local port is set to 0 in
   * the Bind message, then the [[Bound]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param handler The actor which will receive all incoming connection requests
   *                in the form of [[Connected]] messages.
   *
   * @param localAddress The socket address to bind to; use port zero for
   *                automatic assignment (i.e. an ephemeral port, see [[Bound]])
   *
   * @param backlog This specifies the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   *
   * @param options Please refer to the [[SO]] object for a list of all supported options.
   */
  case class Bind(handler: ActorRef,
                  localAddress: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command

  /**
   * This message must be sent to a TCP connection actor after receiving the
   * [[Connected]] message. The connection will not read any data from the
   * socket until this message is received, because this message defines the
   * actor which will receive all inbound data.
   *
   * @param handler The actor which will receive all incoming data and which
   *                will be informed when the connection is closed.
   *
   * @param keepOpenOnPeerClosed If this is set to true then the connection
   *                is not automatically closed when the peer closes its half,
   *                requiring an explicit [[Closed]] from our side when finished.
   *
   * @param useResumeWriting If this is set to true then the connection actor
   *                will refuse all further writes after issuing a [[CommandFailed]]
   *                notification until [[ResumeWriting]] is received. This can
   *                be used to implement NACK-based write backpressure.
   */
  case class Register(handler: ActorRef, keepOpenOnPeerClosed: Boolean = false, useResumeWriting: Boolean = true) extends Command

  /**
   * In order to close down a listening socket, send this message to that socket’s
   * actor (that is the actor which previously had sent the [[Bound]] message). The
   * listener socket actor will reply with a [[Unbound]] message.
   */
  case object Unbind extends Command

  /**
   * Common interface for all commands which aim to close down an open connection.
   */
  sealed trait CloseCommand extends Command {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    def event: ConnectionClosed
  }

  /**
   * A normal close operation will first flush pending writes and then close the
   * socket. The sender of this command and the registered handler for incoming
   * data will both be notified once the socket is closed using a [[Closed]]
   * message.
   */
  case object Close extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = Closed
  }

  /**
   * A confirmed close operation will flush pending writes and half-close the
   * connection, waiting for the peer to close the other half. The sender of this
   * command and the registered handler for incoming data will both be notified
   * once the socket is closed using a [[ConfirmedClosed]] message.
   */
  case object ConfirmedClose extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = ConfirmedClosed
  }

  /**
   * An abort operation will not flush pending writes and will issue a TCP ABORT
   * command to the O/S kernel which should result in a TCP_RST packet being sent
   * to the peer. The sender of this command and the registered handler for
   * incoming data will both be notified once the socket is closed using a
   * [[Aborted]] message.
   */
  case object Abort extends CloseCommand {
    /**
     * The corresponding event which is sent as an acknowledgment once the
     * close operation is finished.
     */
    override def event = Aborted
  }

  /**
   * Each [[WriteCommand]] can optionally request a positive acknowledgment to be sent
   * to the commanding actor. If such notification is not desired the [[WriteCommand#ack]]
   * must be set to an instance of this class. The token contained within can be used
   * to recognize which write failed when receiving a [[CommandFailed]] message.
   */
  case class NoAck(token: Any) extends Event

  /**
   * Default [[NoAck]] instance which is used when no acknowledgment information is
   * explicitly provided. Its “token” is `null`.
   */
  object NoAck extends NoAck(null)

  case class Write(data: Bytes, ack: Event) extends Command {
    /**
     * An acknowledgment is only sent if this write command “wants an ack”, which is
     * equivalent to the [[#ack]] token not being a of type [[NoAck]].
     */
    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }
  object Write {
    /**
     * The empty Write doesn't write anything and isn't acknowledged.
     * It will, however, be denied and sent back with `CommandFailed` if the
     * connection isn't currently ready to send any data (because another WriteCommand
     * is still pending).
     */
    val empty: Write = Write(ByteString.empty, NoAck)

    /**
     * Create a new unacknowledged Write command with the given data.
     */
    def apply(data: Bytes): Write =
      if (data.isEmpty) empty else Write(data, NoAck)
  }

  /**
   * When `useResumeWriting` is in effect as was indicated in the [[Register]] message
   * then this command needs to be sent to the connection actor in order to re-enable
   * writing after a [[CommandFailed]] event. All [[WriteCommand]] processed by the
   * connection actor between the first [[CommandFailed]] and subsequent reception of
   * this message will also be rejected with [[CommandFailed]].
   */
  case object ResumeWriting extends Command

  /**
   * Sending this command to the connection actor will disable reading from the TCP
   * socket. TCP flow-control will then propagate backpressure to the sender side
   * as buffers fill up on either end. To re-enable reading send [[ResumeReading]].
   */
  case object SuspendReading extends Command

  /**
   * This command needs to be sent to the connection actor after a [[SuspendReading]]
   * command in order to resume reading from the socket.
   */
  case object ResumeReading extends Command

  /// EVENTS
  /**
   * Common interface for all events generated by the TCP layer actors.
   */
  trait Event extends Message

  /**
   * Whenever data are read from a socket they will be transferred within this
   * class to the handler actor which was designated in the [[Register]] message.
   */
  case class Received(data: ByteString) extends Event

  /**
   * The connection actor sends this message either to the sender of a [[Connect]]
   * command (for outbound) or to the handler for incoming connections designated
   * in the [[Bind]] message. The connection is characterized by the `remoteAddress`
   * and `localAddress` TCP endpoints.
   */
  case class Connected(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress) extends Event

  /**
   * Whenever a command cannot be completed, the queried actor will reply with
   * this message, wrapping the original command which failed.
   */
  case class CommandFailed(cmd: Command) extends Event

  /**
   * When `useResumeWriting` is in effect as indicated in the [[Register]] message,
   * the [[ResumeWriting]] command will be acknowledged by this message type, upon
   * which it is safe to send at least one write. This means that all writes preceding
   * the first [[CommandFailed]] message have been enqueued to the O/S kernel at this
   * point.
   */
  sealed trait WritingResumed extends Event
  case object WritingResumed extends WritingResumed

  /**
   * The sender of a [[Bind]] command will—in case of success—receive confirmation
   * in this form. If the bind address indicated a 0 port number, then the contained
   * `localAddress` can be used to find out which port was automatically assigned.
   */
  case class Bound(localAddress: InetSocketAddress) extends Event

  /**
   * The sender of an [[Unbind]] command will receive confirmation through this
   * message once the listening socket has been closed.
   */
  sealed trait Unbound extends Event
  case object Unbound extends Unbound

  /**
   * This is the common interface for all events which indicate that a connection
   * has been closed or half-closed.
   */
  sealed trait ConnectionClosed extends Event {
    /**
     * `true` iff the connection has been closed in response to an [[Abort]] command.
     */
    def isAborted: Boolean = false
    /**
     * `true` iff the connection has been fully closed in response to a
     * [[ConfirmedClose]] command.
     */
    def isConfirmed: Boolean = false
    /**
     * `true` iff the connection has been closed by the peer; in case
     * `keepOpenOnPeerClosed` is in effect as per the [[Register]] command,
     * this connection’s reading half is now closed.
     */
    def isPeerClosed: Boolean = false
    /**
     * `true` iff the connection has been closed due to an IO error.
     */
    def isErrorClosed: Boolean = false
    /**
     * If `isErrorClosed` returns true, then the error condition can be
     * retrieved by this method.
     */
    def getErrorCause: String = null
  }
  /**
   * The connection has been closed normally in response to a [[Close]] command.
   */
  case object Closed extends ConnectionClosed
  /**
   * The connection has been aborted in response to an [[Abort]] command.
   */
  case object Aborted extends ConnectionClosed {
    override def isAborted = true
  }
  /**
   * The connection has been half-closed by us and then half-close by the peer
   * in response to a [[ConfirmedClose]] command.
   */
  case object ConfirmedClosed extends ConnectionClosed {
    override def isConfirmed = true
  }
  /**
   * The peer has closed its writing half of the connection.
   */
  case object PeerClosed extends ConnectionClosed {
    override def isPeerClosed = true
  }
  /**
   * The connection has been closed due to an IO error.
   */
  case class ErrorClosed(cause: String) extends ConnectionClosed {
    override def isErrorClosed = true
    override def getErrorCause = cause
  }
}

class TcpExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.tcp"))
  class Settings private[TcpExt] (_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors: Int = getInt("nr-of-selectors") requiring (_ > 0, "nr-of-selectors must be > 0")

    val BatchAcceptLimit: Int = getInt("batch-accept-limit") requiring (_ > 0, "batch-accept-limit must be > 0")
    val DirectBufferSize: Int = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize: Int = getInt("direct-buffer-pool-limit")
    val RegisterTimeout: Duration = getString("register-timeout") match {
      case "infinite" ⇒ Duration.Undefined
      case x          ⇒ Duration(getMilliseconds("register-timeout"), MILLISECONDS)
    }
    val ReceivedMessageSizeLimit: Int = getString("max-received-message-size") match {
      case "unlimited" ⇒ Int.MaxValue
      case x           ⇒ getIntBytes("received-message-size-limit")
    }
    val ManagementDispatcher: String = getString("management-dispatcher")
    val FileIODispatcher: String = getString("file-io-dispatcher")
    val TransferToLimit: Int = getString("file-io-transferTo-limit") match {
      case "unlimited" ⇒ Int.MaxValue
      case _           ⇒ getIntBytes("file-io-transferTo-limit")
    }

    val MaxChannelsPerSelector: Int = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)
    val FinishConnectRetries: Int = getInt("finish-connect-retries") requiring (_ > 0,
      "finish-connect-retries must be > 0")

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      require(size >= 0, s"$path must be non-negative")
      size.toInt
    }
  }

  /**
   *
   */
  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(classOf[TcpManager], this).withDispatcher(Settings.ManagementDispatcher).withDeploy(Deploy.local),
      name = "IO-TCP")
  }

  /**
   * Java API: retrieve a reference to the manager actor.
   */
  def getManager: ActorRef = manager

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)
  val fileIoDispatcher = system.dispatchers.lookup(Settings.FileIODispatcher)
}

/**
 * Java API for accessing socket options.
 */
object TcpSO extends SoJavaFactories {
  import Tcp.SO._

  /**
   * [[akka.io.Inet.SocketOption]] to enable or disable SO_KEEPALIVE
   *
   * For more information see [[java.net.Socket.setKeepAlive]]
   */
  def keepAlive(on: Boolean) = KeepAlive(on)

  /**
   * [[akka.io.Inet.SocketOption]] to enable or disable OOBINLINE (receipt
   * of TCP urgent data) By default, this option is disabled and TCP urgent
   * data is silently discarded.
   *
   * For more information see [[java.net.Socket.setOOBInline]]
   */
  def oobInline(on: Boolean) = OOBInline(on)

  /**
   * [[akka.io.Inet.SocketOption]] to enable or disable TCP_NODELAY
   * (disable or enable Nagle's algorithm)
   *
   * Please note, that TCP_NODELAY is enabled by default.
   *
   * For more information see [[java.net.Socket.setTcpNoDelay]]
   */
  def tcpNoDelay(on: Boolean) = TcpNoDelay(on)
}

object TcpMessage {
  import language.implicitConversions
  import Tcp._

  /**
   * The Connect message is sent to the TCP manager actor, which is obtained via
   * [[TcpExt#getManager]]. Either the manager replies with a [[Tcp.CommandFailed]]
   * or the actor handling the new connection replies with a [[Tcp.Connected]]
   * message.
   *
   * @param remoteAddress is the address to connect to
   * @param localAddress optionally specifies a specific address to bind to
   * @param options Please refer to [[TcpSO]] for a list of all supported options.
   * @param timeout is the desired connection timeout, `null` means "no timeout"
   */
  def connect(remoteAddress: InetSocketAddress,
              localAddress: InetSocketAddress,
              options: JIterable[SocketOption],
              timeout: FiniteDuration): Command = Connect(remoteAddress, Option(localAddress), options, Option(timeout))

  /**
   * Connect to the given `remoteAddress` with an optional `localAddress` to bind to given the specified Socket Options
   */
  def connect(remoteAddress: InetSocketAddress,
              localAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(remoteAddress, Option(localAddress), options, None)

  /**
   * Connect to the given `remoteAddress` without binding to a local address.
   */
  def connect(remoteAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(remoteAddress, None, options, None)
  /**
   * Connect to the given `remoteAddress` without binding to a local address and without
   * specifying options.
   */
  def connect(remoteAddress: InetSocketAddress): Command = Connect(remoteAddress, None, Nil, None)

  /**
   * Connect to the given `remoteAddress` with a connection `timeout` without binding to a local address and without
   * specifying options.
   */
  def connect(remoteAddress: InetSocketAddress, timeout: FiniteDuration): Command = Connect(remoteAddress, None, Nil, Option(timeout))

  /**
   * The Bind message is send to the TCP manager actor, which is obtained via
   * [[TcpExt#getManager]] in order to bind to a listening socket. The manager
   * replies either with a [[Tcp.CommandFailed]] or the actor handling the listen
   * socket replies with a [[Tcp.Bound]] message. If the local port is set to 0 in
   * the Bind message, then the [[Tcp.Bound]] message should be inspected to find
   * the actual port which was bound to.
   *
   * @param handler The actor which will receive all incoming connection requests
   *                in the form of [[Tcp.Connected]] messages.
   *
   * @param localAddress The socket address to bind to; use port zero for
   *                automatic assignment (i.e. an ephemeral port, see [[Bound]])
   *
   * @param backlog This specifies the number of unaccepted connections the O/S
   *                kernel will hold for this port before refusing connections.
   *
   * @param options Please refer to [[TcpSO]] for a list of all supported options.
   */
  def bind(handler: ActorRef,
           endpoint: InetSocketAddress,
           backlog: Int,
           options: JIterable[SocketOption]): Command = Bind(handler, endpoint, backlog, options)
  /**
   * Open a listening socket without specifying options.
   */
  def bind(handler: ActorRef,
           endpoint: InetSocketAddress,
           backlog: Int): Command = Bind(handler, endpoint, backlog, Nil)

  /**
   * This message must be sent to a TCP connection actor after receiving the
   * [[Tcp.Connected]] message. The connection will not read any data from the
   * socket until this message is received, because this message defines the
   * actor which will receive all inbound data.
   *
   * @param handler The actor which will receive all incoming data and which
   *                will be informed when the connection is closed.
   *
   * @param keepOpenOnPeerClosed If this is set to true then the connection
   *                is not automatically closed when the peer closes its half,
   *                requiring an explicit [[Tcp.Closed]] from our side when finished.
   *
   * @param useResumeWriting If this is set to true then the connection actor
   *                will refuse all further writes after issuing a [[Tcp.CommandFailed]]
   *                notification until [[Tcp.ResumeWriting]] is received. This can
   *                be used to implement NACK-based write backpressure.
   */
  def register(handler: ActorRef, keepOpenOnPeerClosed: Boolean, useResumeWriting: Boolean): Command =
    Register(handler, keepOpenOnPeerClosed, useResumeWriting)
  /**
   * The same as `register(handler, false, false)`.
   */
  def register(handler: ActorRef): Command = Register(handler)

  /**
   * In order to close down a listening socket, send this message to that socket’s
   * actor (that is the actor which previously had sent the [[Tcp.Bound]] message). The
   * listener socket actor will reply with a [[Tcp.Unbound]] message.
   */
  def unbind: Command = Unbind

  /**
   * A normal close operation will first flush pending writes and then close the
   * socket. The sender of this command and the registered handler for incoming
   * data will both be notified once the socket is closed using a [[Tcp.Closed]]
   * message.
   */
  def close: Command = Close

  /**
   * A confirmed close operation will flush pending writes and half-close the
   * connection, waiting for the peer to close the other half. The sender of this
   * command and the registered handler for incoming data will both be notified
   * once the socket is closed using a [[Tcp.ConfirmedClosed]] message.
   */
  def confirmedClose: Command = ConfirmedClose

  /**
   * An abort operation will not flush pending writes and will issue a TCP ABORT
   * command to the O/S kernel which should result in a TCP_RST packet being sent
   * to the peer. The sender of this command and the registered handler for
   * incoming data will both be notified once the socket is closed using a
   * [[Tcp.Aborted]] message.
   */
  def abort: Command = Abort

  /**
   * Each [[Tcp.WriteCommand]] can optionally request a positive acknowledgment to be sent
   * to the commanding actor. If such notification is not desired the [[Tcp.WriteCommand#ack]]
   * must be set to an instance of this class. The token contained within can be used
   * to recognize which write failed when receiving a [[Tcp.CommandFailed]] message.
   */
  def noAck(token: AnyRef): NoAck = NoAck(token)
  /**
   * Default [[Tcp.NoAck]] instance which is used when no acknowledgment information is
   * explicitly provided. Its “token” is `null`.
   */
  def noAck: NoAck = NoAck

  /**
   * Write data to the TCP connection. If no ack is needed use the special
   * `NoAck` object. The connection actor will reply with a [[Tcp.CommandFailed]]
   * message if the write could not be enqueued. If [[Tcp.WriteCommand#wantsAck]]
   * returns true, the connection actor will reply with the supplied [[Tcp.WriteCommand#ack]]
   * token once the write has been successfully enqueued to the O/S kernel.
   * <b>Note that this does not in any way guarantee that the data will be
   * or have been sent!</b> Unfortunately there is no way to determine whether
   * a particular write has been sent by the O/S.
   */
  def write(data: Bytes, ack: Event): Command = Write(data, ack)
  /**
   * The same as `write(data, noAck())`.
   */
  def write(data: Bytes): Command = Write(data)

  /**
   * When `useResumeWriting` is in effect as was indicated in the [[Tcp.Register]] message
   * then this command needs to be sent to the connection actor in order to re-enable
   * writing after a [[Tcp.CommandFailed]] event. All [[Tcp.WriteCommand]] processed by the
   * connection actor between the first [[Tcp.CommandFailed]] and subsequent reception of
   * this message will also be rejected with [[Tcp.CommandFailed]].
   */
  def resumeWriting: Command = ResumeWriting

  /**
   * Sending this command to the connection actor will disable reading from the TCP
   * socket. TCP flow-control will then propagate backpressure to the sender side
   * as buffers fill up on either end. To re-enable reading send [[Tcp.ResumeReading]].
   */
  def suspendReading: Command = SuspendReading

  /**
   * This command needs to be sent to the connection actor after a [[Tcp.SuspendReading]]
   * command in order to resume reading from the socket.
   */
  def resumeReading: Command = ResumeReading

  implicit private def fromJava[T](coll: JIterable[T]): immutable.Traversable[T] = {
    akka.japi.Util.immutableSeq(coll)
  }
}
