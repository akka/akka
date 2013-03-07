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

object UdpFF extends ExtensionKey[UdpFFExt] {

  /**
   * Java API: retrieve the UdpFF extension for the given system.
   */
  override def get(system: ActorSystem): UdpFFExt = super.get(system)

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

  sealed trait Bound extends Event
  case object Bound extends Bound

  sealed trait SimpleSendReady extends Event
  case object SimpleSendReady extends SimpleSendReady

  sealed trait Unbound
  case object Unbound extends Unbound

  case class SendFailed(cause: Throwable) extends Event

}

/**
 * Java API: factory methods for the message types used when communicating with the UdpConn service.
 */
object UdpFFMessage {
  import UdpFF._
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

class UdpFFExt(system: ExtendedActorSystem) extends IO.Extension {

  val settings: UdpSettings = new UdpSettings(system.settings.config.getConfig("akka.io.udp-fire-and-forget"))

  val manager: ActorRef = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props(new UdpFFManager(this)),
      name = "IO-UDP-FF")
  }

  val bufferPool: BufferPool = new DirectByteBufferPool(settings.DirectBufferSize, settings.MaxDirectBufferPoolSize)
}