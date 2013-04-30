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
import java.lang.{ Iterable ⇒ JIterable }

object UdpConnected extends ExtensionKey[UdpConnectedExt] {
  /**
   * Java API: retrieve the UdpConnected extension for the given system.
   */
  override def get(system: ActorSystem): UdpConnectedExt = super.get(system)

  trait Command extends IO.HasFailureMessage {
    def failureMessage = CommandFailed(this)
  }

  case class NoAck(token: Any)
  object NoAck extends NoAck(null)

  case class Send(payload: ByteString, ack: Any) extends Command {
    require(ack != null, "ack must be non-null. Use NoAck if you don't want acks.")

    def wantsAck: Boolean = !ack.isInstanceOf[NoAck]
  }
  object Send {
    def apply(data: ByteString): Send = Send(data, NoAck)
  }

  case class Connect(handler: ActorRef,
                     remoteAddress: InetSocketAddress,
                     localAddress: Option[InetSocketAddress] = None,
                     options: immutable.Traversable[SocketOption] = Nil) extends Command

  case object StopReading extends Command
  case object ResumeReading extends Command

  trait Event

  case class Received(data: ByteString) extends Event
  case class CommandFailed(cmd: Command) extends Event

  sealed trait Connected extends Event
  case object Connected extends Connected

  sealed trait Disconnected extends Event
  case object Disconnected extends Disconnected

  case object Close extends Command

  case class SendFailed(cause: Throwable) extends Event

}

class UdpConnectedExt(system: ExtendedActorSystem) extends IO.Extension {

  val settings: UdpSettings = new UdpSettings(system.settings.config.getConfig("akka.io.udp-connected"))

  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(classOf[UdpConnectedManager], this),
      name = "IO-UDP-CONN")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)

}

/**
 * Java API: factory methods for the message types used when communicating with the UdpConnected service.
 */
object UdpConnectedMessage {
  import language.implicitConversions
  import UdpConnected._

  def connect(handler: ActorRef,
              remoteAddress: InetSocketAddress,
              localAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(handler, remoteAddress, Some(localAddress), options)
  def connect(handler: ActorRef,
              remoteAddress: InetSocketAddress,
              options: JIterable[SocketOption]): Command = Connect(handler, remoteAddress, None, options)
  def connect(handler: ActorRef,
              remoteAddress: InetSocketAddress): Command = Connect(handler, remoteAddress, None, Nil)

  def send(data: ByteString): Command = Send(data)
  def send(data: ByteString, ack: AnyRef): Command = Send(data, ack)

  def close: Command = Close

  def noAck: NoAck = NoAck
  def noAck(token: AnyRef): NoAck = NoAck(token)

  def stopReading: Command = StopReading
  def resumeReading: Command = ResumeReading

  implicit private def fromJava[T](coll: JIterable[T]): immutable.Traversable[T] = {
    import scala.collection.JavaConverters._
    coll.asScala.to
  }
}
