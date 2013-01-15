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

object Tcp extends ExtensionKey[TcpExt] {

  // Java API
  override def get(system: ActorSystem): TcpExt = system.extension(this)

  /// COMMANDS
  sealed trait Command

  case class Connect(remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None) extends Command
  case class Bind(handler: ActorRef,
                  address: InetSocketAddress,
                  backlog: Int = 100,
                  options: immutable.Seq[SocketOption] = Nil) extends Command
  case object Unbind extends Command
  case class Register(handler: ActorRef) extends Command

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
  object SO {
    // shared socket options

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to set the SO_RCVBUF option
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
     * [[akka.io.Tcp.SO.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    case class ReuseAddress(on: Boolean) extends SocketOption {
      override def beforeBind(s: ServerSocket): Unit = s.setReuseAddress(on)
      override def beforeConnect(s: Socket): Unit = s.setReuseAddress(on)
    }

    // general socket options

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to enable or disable SO_KEEPALIVE
     *
     * For more information see [[java.net.Socket.setKeepAlive]]
     */
    case class KeepAlive(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setKeepAlive(on)
    }

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to enable or disable OOBINLINE (receipt
     * of TCP urgent data) By default, this option is disabled and TCP urgent
     * data is silently discarded.
     *
     * For more information see [[java.net.Socket.setOOBInline]]
     */
    case class OOBInline(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setOOBInline(on)
    }

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    case class SendBufferSize(size: Int) extends SocketOption {
      require(size > 0, "SendBufferSize must be > 0")
      override def afterConnect(s: Socket): Unit = s.setSendBufferSize(size)
    }

    // SO_LINGER is handled by the Close code

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to enable or disable TCP_NODELAY
     * (disable or enable Nagle's algorithm)
     *
     * For more information see [[java.net.Socket.setTcpNoDelay]]
     */
    case class TcpNoDelay(on: Boolean) extends SocketOption {
      override def afterConnect(s: Socket): Unit = s.setTcpNoDelay(on)
    }

    /**
     * [[akka.io.Tcp.SO.SocketOption]] to set the traffic class or
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

  // TODO: what about close reasons?
  sealed trait CloseCommand extends Command

  case object Close extends CloseCommand
  case object ConfirmedClose extends CloseCommand
  case object Abort extends CloseCommand

  case class Write(data: ByteString, ack: AnyRef) extends Command
  object Write {
    val Empty: Write = Write(ByteString.empty, null)
    def apply(data: ByteString): Write =
      if (data.isEmpty) Empty else Write(data, null)
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
  case class ErrorClose(cause: Throwable) extends ConnectionClosed

  /// INTERNAL
  case class RegisterClientChannel(channel: SocketChannel)
  case class RegisterServerChannel(channel: ServerSocketChannel)
  case class CreateConnection(channel: SocketChannel)
  case class Reject(command: Command, commander: ActorRef)
  // Retry should be sent by Selector actors to their parent router with retriesLeft decremented. If retries are
  // depleted, the selector actor must reply directly to the manager with a Reject (above).
  case class Retry(command: Command, retriesLeft: Int, commander: ActorRef) {
    require(retriesLeft >= 0, "The upper limit for retries must be nonnegative.")
  }
  case object ChannelConnectable
  case object ChannelAcceptable
  case object ChannelReadable
  case object ChannelWritable
  case object AcceptInterest
  case object ReadInterest
  case object WriteInterest
}

class TcpExt(system: ExtendedActorSystem) extends IO.Extension {

  object Settings {
    val config = system.settings.config.getConfig("akka.io.tcp")
    import config._

    val NrOfSelectors = getInt("nr-of-selectors")
    val MaxChannels = getInt("max-channels")
    val MaxChannelsPerSelector = MaxChannels / NrOfSelectors
    val SelectTimeout =
      if (getString("select-timeout") == "infinite") Duration.Inf
      else Duration(getMilliseconds("select-timeout"), MILLISECONDS)
    val SelectorAssociationRetries = getInt("selector-association-retries")
    val SelectorDispatcher = getString("selector-dispatcher")
    val WorkerDispatcher = getString("worker-dispatcher")
    val ManagementDispatcher = getString("management-dispatcher")
    val DirectBufferSize = getInt("direct-buffer-size")
    val RegisterTimeout =
      if (getString("register-timeout") == "infinite") Duration.Undefined
      else Duration(getMilliseconds("register-timeout"), MILLISECONDS)
  }

  val manager = system.asInstanceOf[ActorSystemImpl].systemActorOf(
    Props[TcpManager].withDispatcher(Settings.ManagementDispatcher), "IO-TCP")

}
