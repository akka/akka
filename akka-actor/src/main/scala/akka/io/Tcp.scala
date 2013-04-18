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
import akka.util.Helpers.Requiring
import akka.actor._
import java.lang.{ Iterable ⇒ JIterable }

object Tcp extends ExtensionKey[TcpExt] {

  /**
   * Java API: retrieve Tcp extension for the given system.
   */
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

  /**
   * This is the common trait for all commands understood by TCP actors.
   */
  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  /**
   * The Connect message is sent to the [[TcpManager]], which is obtained via
   * [[TcpExt#getManager]]. Either the manager replies with a [[CommandFailed]]
   * or the actor handling the new connection replies with a [[Connected]]
   * message.
   */
  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command
  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command

  case class Register(handler: ActorRef, keepOpenOnPeerClosed: Boolean = false, useResumeWriting: Boolean = true) extends Command
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

  sealed trait WriteCommand extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def ack: Any
    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }

  /**
   * Write data to the TCP connection. If no ack is needed use the special
   * `NoAck` object.
   */
  case class Write(data: ByteString, ack: Any) extends WriteCommand
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
    def apply(data: ByteString): Write =
      if (data.isEmpty) empty else Write(data, NoAck)
  }

  /**
   * Write `count` bytes starting at `position` from file at `filePath` to the connection.
   * When write is finished acknowledge with `ack`. If no ack is needed use `NoAck`. The
   * count must be > 0.
   */
  case class WriteFile(filePath: String, position: Long, count: Long, ack: Any) extends WriteCommand {
    require(position >= 0, "WriteFile.position must be >= 0")
    require(count > 0, "WriteFile.count must be > 0")
  }

  case object ResumeWriting extends Command

  case object SuspendReading extends Command
  case object ResumeReading extends Command

  /// EVENTS
  trait Event

  case class Received(data: ByteString) extends Event
  case class Connected(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress) extends Event
  case class CommandFailed(cmd: Command) extends Event

  sealed trait WritingResumed extends Event
  case object WritingResumed extends WritingResumed

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
    val FileIODispatcher = getString("file-io-dispatcher")
    val TransferToLimit = getString("file-io-transferTo-limit") match {
      case "unlimited" ⇒ Int.MaxValue
      case _           ⇒ getIntBytes("file-io-transferTo-limit")
    }

    val MaxChannelsPerSelector: Int = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)

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

  /**
   * Java API: retrieve a reference to the manager actor.
   */
  def getManager: ActorRef = manager

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)
  val fileIoDispatcher = system.dispatchers.lookup(Settings.FileIODispatcher)
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
  def register(handler: ActorRef, keepOpenOnPeerClosed: Boolean, useResumeWriting: Boolean): Command =
    Register(handler, keepOpenOnPeerClosed, useResumeWriting)
  def unbind: Command = Unbind

  def close: Command = Close
  def confirmedClose: Command = ConfirmedClose
  def abort: Command = Abort

  def noAck: NoAck = NoAck
  def noAck(token: AnyRef): NoAck = NoAck(token)

  def write(data: ByteString): Command = Write(data)
  def write(data: ByteString, ack: AnyRef): Command = Write(data, ack)

  def suspendReading: Command = SuspendReading
  def resumeReading: Command = ResumeReading

  def resumeWriting: Command = ResumeWriting

  implicit private def fromJava[T](coll: JIterable[T]): immutable.Traversable[T] = {
    import scala.collection.JavaConverters._
    coll.asScala.to
  }
}
