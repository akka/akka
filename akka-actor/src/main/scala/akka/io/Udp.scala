/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.DatagramSocket
import akka.io.Inet.{ SoJavaFactories, SocketOption }
import com.typesafe.config.Config
import akka.actor.{ Props, ActorSystemImpl }
import akka.actor.ExtendedActorSystem
import akka.actor.ActorRef
import akka.actor.ExtensionKey
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.util.Helpers.Requiring
import java.net.InetSocketAddress
import scala.collection.immutable

object Udp extends ExtensionKey[UdpExt] {

  /**
   * Java API: retrieve the Udp extension for the given system.
   */
  override def get(system: ActorSystem): UdpExt = super.get(system)

  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  case class NoAck(token: Any)
  object NoAck extends NoAck(null)

  case class Send(payload: ByteString, target: InetSocketAddress, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }
  object Send {
    def apply(data: ByteString, target: InetSocketAddress): Send = Send(data, target, NoAck)
  }

  case class Bind(handler: ActorRef,
                  endpoint: InetSocketAddress,
                  options: immutable.Traversable[SocketOption] = Nil) extends Command
  case object Unbind extends Command

  case class SimpleSender(options: immutable.Traversable[SocketOption] = Nil) extends Command
  object SimpleSender extends SimpleSender(Nil)

  case object StopReading extends Command
  case object ResumeReading extends Command

  trait Event

  case class Received(data: ByteString, sender: InetSocketAddress) extends Event
  case class CommandFailed(cmd: Command) extends Event

  case class Bound(localAddress: InetSocketAddress) extends Event

  sealed trait SimpleSendReady extends Event
  case object SimpleSendReady extends SimpleSendReady

  sealed trait Unbound
  case object Unbound extends Unbound

  case class SendFailed(cause: Throwable) extends Event

  object SO extends Inet.SoForwarders {

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_BROADCAST option
     *
     * For more information see [[java.net.DatagramSocket#setBroadcast]]
     */
    case class Broadcast(on: Boolean) extends SocketOption {
      override def beforeDatagramBind(s: DatagramSocket): Unit = s.setBroadcast(on)
    }

  }

  private[io] class UdpSettings(_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors: Int = getInt("nr-of-selectors") requiring (_ > 0, "nr-of-selectors must be > 0")
    val DirectBufferSize: Int = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize: Int = getInt("direct-buffer-pool-limit")
    val BatchReceiveLimit: Int = getInt("receive-throughput")

    val ManagementDispatcher: String = getString("management-dispatcher")

    override val MaxChannelsPerSelector: Int = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      size.toInt
    }
  }
}

class UdpExt(system: ExtendedActorSystem) extends IO.Extension {

  import Udp.UdpSettings

  val settings: UdpSettings = new UdpSettings(system.settings.config.getConfig("akka.io.udp"))

  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new UdpManager(this)),
      name = "IO-UDP-FF")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)
}

/**
 * Java API: factory methods for the message types used when communicating with the Udp service.
 */
object UdpMessage {
  import Udp._
  import java.lang.{ Iterable ⇒ JIterable }
  import scala.collection.JavaConverters._
  import language.implicitConversions

  def send(payload: ByteString, target: InetSocketAddress): Send = Send(payload, target)
  def send(payload: ByteString, target: InetSocketAddress, ack: Any): Send = Send(payload, target, ack)

  def bind(handler: ActorRef, endpoint: InetSocketAddress, options: JIterable[SocketOption]): Bind =
    Bind(handler, endpoint, options.asScala.to)

  def bind(handler: ActorRef, endpoint: InetSocketAddress): Bind = Bind(handler, endpoint, Nil)

  def simpleSender(options: JIterable[SocketOption]): SimpleSender = SimpleSender(options.asScala.to)
  def simpleSender: SimpleSender = SimpleSender

  def unbind: Unbind.type = Unbind

  def stopReading: StopReading.type = StopReading
  def resumeReading: ResumeReading.type = ResumeReading
}

object UdpSO extends SoJavaFactories {
  import Udp.SO._
  def broadcast(on: Boolean) = Broadcast(on)
}
