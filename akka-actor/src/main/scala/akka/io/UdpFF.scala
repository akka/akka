/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor._
import akka.util.ByteString
import java.net.{ DatagramSocket, InetSocketAddress }
import scala.collection.immutable
import com.typesafe.config.Config
import akka.io.Inet.SocketOption

object UdpFF extends ExtensionKey[UdpFFExt] {

  // Java API
  override def get(system: ActorSystem): UdpFFExt = system.extension(this)

  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

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