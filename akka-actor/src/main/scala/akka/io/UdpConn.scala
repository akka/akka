/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor._
import akka.io.Inet.SocketOption
import akka.io.Udp.UdpSettings
import akka.util.ByteString
import java.net.InetSocketAddress
import scala.collection.immutable

object UdpConn extends ExtensionKey[UdpConnExt] {
  // Java API
  override def get(system: ActorSystem): UdpConnExt = system.extension(this)

  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  case object NoAck
  case class Send(payload: ByteString, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = ack != NoAck
  }
  object Send {
    def apply(data: ByteString): Send = Send(data, NoAck)
  }

  case class Connect(handler: ActorRef,
                     localAddress: Option[InetSocketAddress],
                     remoteAddress: InetSocketAddress,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command

  case object StopReading extends Command
  case object ResumeReading extends Command

  trait Event

  case class Received(data: ByteString) extends Event
  case class CommandFailed(cmd: Command) extends Event
  case object Connected extends Event
  case object Disconnected extends Event

  case object Close extends Command

  case class SendFailed(cause: Throwable) extends Event

}

class UdpConnExt(system: ExtendedActorSystem) extends IO.Extension {

  val settings = new UdpSettings(system.settings.config.getConfig("akka.io.udp-fire-and-forget"))

  val manager = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new UdpConnManager(this)),
      name = "IO-UDP-CONN")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)

}