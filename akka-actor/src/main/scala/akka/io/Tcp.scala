/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.net.Socket
import akka.io.Inet.SocketOption
import com.typesafe.config.Config
import scala.concurrent.duration._
import scala.collection.immutable
import akka.util.ByteString
import akka.actor._

object Tcp extends ExtensionKey[TcpExt] {

  // Java API
  override def get(system: ActorSystem): TcpExt = system.extension(this)

  // shared socket options
  object SO {

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
     * For more information see [[java.net.Socket.setTcpNoDelay]]
     */
    case class TcpNoDelay(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setTcpNoDelay(on)
    }

  }

  /// COMMANDS
  trait Command

  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command
  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command
  case class Register(handler: ActorRef) extends Command
  case object Unbind extends Command

  sealed trait CloseCommand extends Command
  case object Close extends CloseCommand
  case object ConfirmedClose extends CloseCommand
  case object Abort extends CloseCommand

  case object NoAck

  /**
   * Write data to the TCP connection. If no ack is needed use the special
   * `NoAck` object.
   */
  case class Write(data: ByteString, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = ack != NoAck
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
  case object Bound extends Event
  case object Unbound extends Event

  sealed trait ConnectionClosed extends Event
  case object Closed extends ConnectionClosed
  case object Aborted extends ConnectionClosed
  case object ConfirmedClosed extends ConnectionClosed
  case object PeerClosed extends ConnectionClosed
  case class ErrorClosed(cause: String) extends ConnectionClosed
}

class TcpExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.tcp"))
  // FIXME: get away with subclassess
  class Settings private[TcpExt] (_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors = getInt("nr-of-selectors")

    val BatchAcceptLimit = getInt("batch-accept-limit")
    val DirectBufferSize = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize = getInt("max-direct-buffer-pool-size")
    val RegisterTimeout = getString("register-timeout") match {
      case "infinite" ⇒ Duration.Undefined
      case x          ⇒ Duration(x)
    }
    val ReceivedMessageSizeLimit = getString("received-message-size-limit") match {
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

  val manager = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new TcpManager(this)).withDispatcher(Settings.ManagementDispatcher),
      name = "IO-TCP")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)
}
