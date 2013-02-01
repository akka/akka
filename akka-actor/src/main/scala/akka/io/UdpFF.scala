/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor._
import akka.util.ByteString
import java.net.{ DatagramSocket, Socket, InetSocketAddress }
import scala.collection.immutable
import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import java.nio.ByteBuffer

object UdpFF extends ExtensionKey[UdpFFExt] {

  // Java API
  override def get(system: ActorSystem): UdpFFExt = system.extension(this)

  /**
   * SocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a socket).
   */
  sealed trait SocketOption {
    /**
     * Action to be taken for this option before calling bind()
     */
    def beforeBind(s: DatagramSocket): Unit = ()

  }

  object SO {

    /**
     * [[akka.io.UdpFF.SocketOption]] to set the SO_BROADCAST option
     *
     * For more information see [[java.net.DatagramSocket#setBroadcast]]
     */
    case class Broadcast(on: Boolean) extends SocketOption {
      override def beforeBind(s: DatagramSocket): Unit = s.setBroadcast(on)
    }

    /**
     * [[akka.io.UdpFF.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket#setReceiveBufferSize]]
     */
    case class ReceiveBufferSize(size: Int) extends SocketOption {
      require(size > 0, "ReceiveBufferSize must be > 0")
      override def beforeBind(s: DatagramSocket): Unit = s.setReceiveBufferSize(size)
    }

    /**
     * [[akka.io.UdpFF.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket#setReuseAddress]]
     */
    case class ReuseAddress(on: Boolean) extends SocketOption {
      override def beforeBind(s: DatagramSocket): Unit = s.setReuseAddress(on)
    }

    /**
     * [[akka.io.UdpFF.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket#setSendBufferSize]]
     */
    case class SendBufferSize(size: Int) extends SocketOption {
      require(size > 0, "SendBufferSize must be > 0")
      override def beforeBind(s: DatagramSocket): Unit = s.setSendBufferSize(size)
    }

    /**
     * [[akka.io.UdpFF.SocketOption]] to set the traffic class or
     * type-of-service octet in the IP header for packets sent from this
     * socket.
     *
     * For more information see [[java.net.Socket#setTrafficClass]]
     */
    case class TrafficClass(tc: Int) extends SocketOption {
      require(0 <= tc && tc <= 255, "TrafficClass needs to be in the interval [0, 255]")
      override def beforeBind(s: DatagramSocket): Unit = s.setTrafficClass(tc)
    }
  }

  trait Command

  case object NoAck
  case class Send(payload: ByteString, target: InetSocketAddress, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = ack != NoAck
  }
  object Send {
    def apply(data: ByteString, target: InetSocketAddress): Send = Send(data, target, NoAck)
  }

  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command
  case object Unbind extends Command

  case object SimpleSender extends Command

  case object StopReading extends Command
  case object ResumeReading extends Command

  trait Event

  case class Received(data: ByteString, sender: InetSocketAddress) extends Event
  case class CommandFailed(cmd: Command) extends Event
  case object Bound extends Event
  case object SimpleSendReady extends Event
  case object Unbound extends Event

  sealed trait CloseCommand extends Command
  case object Close extends CloseCommand
  case object Abort extends CloseCommand

  case class SendFailed(cause: Throwable) extends Event

}

class UdpFFExt(system: ExtendedActorSystem) extends IO.Extension {

  val settings = new Settings(system.settings.config.getConfig("akka.io.udpFF"))
  class Settings private[UdpFFExt] (_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors = getInt("nr-of-selectors")
    val DirectBufferSize = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize = getInt("max-direct-buffer-pool-size")

    val ManagementDispatcher = getString("management-dispatcher")

    require(NrOfSelectors > 0, "nr-of-selectors must be > 0")

    override val MaxChannelsPerSelector = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      size.toInt
    }
  }

  val manager = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new UdpFFManager(this)),
      name = "IO-UDP-FF")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)
}

trait WithUdpFFBufferPool {
  def udpFF: UdpFFExt

  def acquireBuffer(): ByteBuffer =
    udpFF.bufferPool.acquire()

  def releaseBuffer(buffer: ByteBuffer): Unit =
    udpFF.bufferPool.release(buffer)
}