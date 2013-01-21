/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.{ ServerSocketChannel, SocketChannel }
import akka.actor.ActorRef
import akka.util.ByteString
import akka.actor.ExtensionKey
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystemImpl
import akka.actor.Props
import java.net.Socket
import java.net.ServerSocket
import scala.concurrent.duration._
import scala.collection.immutable
import akka.actor.ActorSystem
import com.typesafe.config.Config

object Tcp extends ExtensionKey[TcpExt] {

  // Java API
  override def get(system: ActorSystem): TcpExt = system.extension(this)

  /**
   * SocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a socket).
   */
  sealed trait SocketOption {
    /**
     * Action to be taken for this option before calling bind()
     */
    def beforeBind(s: ServerSocket): Unit = ()
    /**
     * Action to be taken for this option before calling connect()
     */
    def beforeConnect(s: Socket): Unit = ()
    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(s: Socket): Unit = ()
  }

  // shared socket options
  object SO {

    /**
     * [[akka.io.Tcp.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket.setReceiveBufferSize]]
     */
    case class ReceiveBufferSize(size: Int) extends SocketOption {
      require(size > 0, "ReceiveBufferSize must be > 0")
      override def beforeBind(s: ServerSocket): Unit = s.setReceiveBufferSize(size)
      override def beforeConnect(s: Socket): Unit = s.setReceiveBufferSize(size)
    }

    // server socket options

    /**
     * [[akka.io.Tcp.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    case class ReuseAddress(on: Boolean) extends SocketOption {
      override def beforeBind(s: ServerSocket): Unit = s.setReuseAddress(on)
      override def beforeConnect(s: Socket): Unit = s.setReuseAddress(on)
    }

    // general socket options

    /**
     * [[akka.io.Tcp.SocketOption]] to enable or disable SO_KEEPALIVE
     *
     * For more information see [[java.net.Socket.setKeepAlive]]
     */
    case class KeepAlive(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setKeepAlive(on)
    }

    /**
     * [[akka.io.Tcp.SocketOption]] to enable or disable OOBINLINE (receipt
     * of TCP urgent data) By default, this option is disabled and TCP urgent
     * data is silently discarded.
     *
     * For more information see [[java.net.Socket.setOOBInline]]
     */
    case class OOBInline(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setOOBInline(on)
    }

    /**
     * [[akka.io.Tcp.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    case class SendBufferSize(size: Int) extends SocketOption {
      require(size > 0, "SendBufferSize must be > 0")
      override def afterConnect(s: Socket): Unit = s.setSendBufferSize(size)
    }

    // SO_LINGER is handled by the Close code

    /**
     * [[akka.io.Tcp.SocketOption]] to enable or disable TCP_NODELAY
     * (disable or enable Nagle's algorithm)
     *
     * For more information see [[java.net.Socket.setTcpNoDelay]]
     */
    case class TcpNoDelay(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setTcpNoDelay(on)
    }

    /**
     * [[akka.io.Tcp.SocketOption]] to set the traffic class or
     * type-of-service octet in the IP header for packets sent from this
     * socket.
     *
     * For more information see [[java.net.Socket.setTrafficClass]]
     */
    case class TrafficClass(tc: Int) extends SocketOption {
      require(0 <= tc && tc <= 255, "TrafficClass needs to be in the interval [0, 255]")
      override def afterConnect(s: Socket): Unit = s.setTrafficClass(tc)
    }
  }

  /// COMMANDS
  sealed trait Command

  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: Traversable[SocketOption] = Nil) extends Command
  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  backlog: Int = 100,
                  options: Traversable[SocketOption] = Nil) extends Command
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
  sealed trait Event

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
  case class ErrorClose(cause: String) extends ConnectionClosed

  /// INTERNAL
  case class RegisterOutgoingConnection(channel: SocketChannel)
  case class RegisterServerSocketChannel(channel: ServerSocketChannel)
  case class RegisterIncomingConnection(channel: SocketChannel, handler: ActorRef,
                                        options: Traversable[SocketOption]) extends Command
  case class Retry(command: Command, retriesLeft: Int) { require(retriesLeft >= 0) }
  case object ChannelConnectable
  case object ChannelAcceptable
  case object ChannelReadable
  case object ChannelWritable
  case object AcceptInterest
  case object ReadInterest
  case object WriteInterest
}

class TcpExt(system: ExtendedActorSystem) extends IO.Extension {

  val Settings = new Settings(system.settings.config.getConfig("akka.io.tcp"))
  class Settings private[TcpExt] (config: Config) {
    import config._

    val NrOfSelectors = getInt("nr-of-selectors")
    val MaxChannels = getInt("max-channels")
    val SelectTimeout = getString("select-timeout") match {
      case "infinite" ⇒ Duration.Inf
      case x          ⇒ Duration(x)
    }
    val SelectorAssociationRetries = getInt("selector-association-retries")
    val BatchAcceptLimit = getInt("batch-accept-limit")
    val DirectBufferSize = getInt("direct-buffer-size")
    val MaxDirectBufferPoolSize = getInt("max-direct-buffer-pool-size")
    val RegisterTimeout = getString("register-timeout") match {
      case "infinite" ⇒ Duration.Undefined
      case x          ⇒ Duration(x)
    }
    val SelectorDispatcher = getString("selector-dispatcher")
    val WorkerDispatcher = getString("worker-dispatcher")
    val ManagementDispatcher = getString("management-dispatcher")
    val TraceLogging = getBoolean("trace-logging")

    require(NrOfSelectors > 0, "nr-of-selectors must be > 0")
    require(MaxChannels >= 0, "max-channels must be >= 0")
    require(SelectTimeout >= Duration.Zero, "select-timeout must not be negative")
    require(SelectorAssociationRetries >= 0, "selector-association-retries must be >= 0")
    require(BatchAcceptLimit > 0, "batch-accept-limit must be > 0")

    val MaxChannelsPerSelector = MaxChannels / NrOfSelectors
  }

  val manager = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new TcpManager(this)).withDispatcher(Settings.ManagementDispatcher),
      name = "IO-TCP")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(Settings.DirectBufferSize, Settings.MaxDirectBufferPoolSize)
}
