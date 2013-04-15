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
import akka.util.ByteString
import akka.actor._
import java.lang.{ Iterable ⇒ JIterable }

object Tcp extends ExtensionKey[TcpExt] {

  // Java API
  override def get(system: ActorSystem): TcpExt = super.get(system)

  // shared socket options
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

  /// COMMANDS
  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command
  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command

  case class Register(handler: ActorRef, keepOpenOnPeerClosed: Boolean = false) extends Command
  case object Unbind extends Command

  sealed trait CloseCommand extends Command {
    def event: ConnectionClosed
  }
  case object Close extends CloseCommand {
    override def event = Closed
  }
  case object ConfirmedClose extends CloseCommand {
    override def event = ConfirmedClosed
  }
  case object Abort extends CloseCommand {
    override def event = Aborted
  }

  case class NoAck(token: Any)
  object NoAck extends NoAck(null)

  /**
   * Write data to the TCP connection. If no ack is needed use the special
   * `NoAck` object.
   */
  case class Write(data: ByteString, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }
  object Write {
    val Empty: Write = Write(ByteString.empty, NoAck)
    def apply(data: ByteString): Write =
      if (data.isEmpty) Empty else Write(data, NoAck)
  }

  case object StopReading extends Command
  case object ResumeReading extends Command

  /// EVENTS
  trait Event

  case class Received(data: ByteString) extends Event
  case class Connected(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress) extends Event
  case class CommandFailed(cmd: Command) extends Event

  case class Bound(localAddress: InetSocketAddress) extends Event
  sealed trait Unbound extends Event
  case object Unbound extends Unbound

  sealed trait ConnectionClosed extends Event {
    def isAborted: Boolean = false
    def isConfirmed: Boolean = false
    def isPeerClosed: Boolean = false
    def isErrorClosed: Boolean = false
    def getErrorCause: String = null
  }
  case object Closed extends ConnectionClosed
  case object Aborted extends ConnectionClosed {
    override def isAborted = true
  }
  case object ConfirmedClosed extends ConnectionClosed {
    override def isConfirmed = true
  }
  case object PeerClosed extends ConnectionClosed {
    override def isPeerClosed = true
  }
  case class ErrorClosed(cause: String) extends ConnectionClosed {
    override def isErrorClosed = true
    override def getErrorCause = cause
  }
}

class TcpExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.tcp"))
  class Settings private[TcpExt] (_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors = getInt("nr-of-selectors")

    val BatchAcceptLimit = getInt("batch-accept-limit")
    val DirectBufferSize = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize = getInt("direct-buffer-pool-limit")
    val RegisterTimeout = getString("register-timeout") match {
      case "infinite" ⇒ Duration.Undefined
      case x          ⇒ Duration(x)
    }
    val ReceivedMessageSizeLimit = getString("max-received-message-size") match {
      case "unlimited" ⇒ Int.MaxValue
      case x           ⇒ getIntBytes("received-message-size-limit")
    }
    val ManagementDispatcher = getString("management-dispatcher")

    require(NrOfSelectors > 0, "nr-of-selectors must be > 0")
    require(MaxChannels == -1 || MaxChannels > 0, "max-channels must be > 0 or 'unlimited'")
    require(SelectTimeout >= Duration.Zero, "select-timeout must not be negative")
    require(SelectorAssociationRetries >= 0, "selector-association-retries must be >= 0")
    require(BatchAcceptLimit > 0, "batch-accept-limit must be > 0")

    val MaxChannelsPerSelector = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      size.toInt
    }
  }

  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new TcpManager(this)).withDispatcher(Settings.ManagementDispatcher),
      name = "IO-TCP")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)
}

object TcpSO extends SoJavaFactories {
  import Tcp.SO._
  def keepAlive(on: Boolean) = KeepAlive(on)
  def oobInline(on: Boolean) = OOBInline(on)
  def tcpNoDelay(on: Boolean) = TcpNoDelay(on)
}

object TcpMessage {
  import language.implicitConversions
  import Tcp._

  def connect(remoteAddress: InetSocketAddress,
              localAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(remoteAddress, Some(localAddress), options)
  def connect(remoteAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(remoteAddress, None, options)
  def connect(remoteAddress: InetSocketAddress): Command = Connect(remoteAddress, None, Nil)

  def bind(handler: ActorRef,
           endpoint: InetSocketAddress,
           backlog: Int,
           options: JIterable[SocketOption]): Command = Bind(handler, endpoint, backlog, options)
  def bind(handler: ActorRef,
           endpoint: InetSocketAddress,
           backlog: Int): Command = Bind(handler, endpoint, backlog, Nil)

  def register(handler: ActorRef): Command = Register(handler)
  def register(handler: ActorRef, keepOpenOnPeerClosed: Boolean): Command = Register(handler, keepOpenOnPeerClosed)
  def unbind: Command = Unbind

  def close: Command = Close
  def confirmedClose: Command = ConfirmedClose
  def abort: Command = Abort

  def noAck: NoAck = NoAck
  def noAck(token: AnyRef): NoAck = NoAck(token)

  def write(data: ByteString): Command = Write(data)
  def write(data: ByteString, ack: AnyRef): Command = Write(data, ack)

  def stopReading: Command = StopReading
  def resumeReading: Command = ResumeReading

  implicit private def fromJava[T](coll: JIterable[T]): immutable.Traversable[T] = {
    import scala.collection.JavaConverters._
    coll.asScala.to
  }
}
