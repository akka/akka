/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import com.google.protobuf.Message
import org.zeromq.{ ZMQ ⇒ JZMQ }
import akka.actor.ActorRef
import akka.util.duration._
import akka.util.Duration

sealed trait Request
sealed trait SocketOption extends Request
sealed trait SocketMeta extends SocketOption
sealed trait SocketConnectOption extends SocketOption {
  def endpoint: String
}
sealed trait PubSubOption extends SocketOption {
  def payload: Seq[Byte]
}
sealed trait SocketOptionQuery extends Request

case class Connect(endpoint: String) extends SocketConnectOption

object Context {
  def apply(numIoThreads: Int = 1) = new Context(numIoThreads)
}
class Context(numIoThreads: Int) extends SocketMeta {
  private val context = JZMQ.context(numIoThreads)
  def socket(socketType: SocketType.ZMQSocketType) = {
    context.socket(socketType.id)
  }
  def poller = {
    context.poller
  }
  def term = {
    context.term
  }
}

trait Deserializer extends SocketOption {
  def apply(frames: Seq[Frame]): Any
}

object SocketType {
  abstract class ZMQSocketType(val id: Int) extends SocketMeta
  object Pub extends ZMQSocketType(JZMQ.PUB)
  object Sub extends ZMQSocketType(JZMQ.SUB)
  object Dealer extends ZMQSocketType(JZMQ.DEALER)
  object Router extends ZMQSocketType(JZMQ.ROUTER)
}

case class Listener(listener: ActorRef) extends SocketMeta
case class PollDispatcher(name: String) extends SocketMeta
case class PollTimeoutDuration(duration: Duration = 100 millis) extends SocketMeta

case class Bind(endpoint: String) extends SocketConnectOption
private[zeromq] case object Close extends Request

case class Subscribe(payload: Seq[Byte]) extends PubSubOption
object Subscribe {
  def apply(topic: String): Subscribe = new Subscribe(topic.getBytes)
}

case class Unsubscribe(payload: Seq[Byte]) extends PubSubOption
object Unsubscribe {
  def apply(topic: String): Unsubscribe = Unsubscribe(topic.getBytes)
}

case class Send(frames: Seq[Frame]) extends Request

case class ZMQMessage(frames: Seq[Frame]) {
  def firstFrameAsString = new String(frames.head.payload.toArray)
}
object ZMQMessage {
  def apply(bytes: Array[Byte]): ZMQMessage = ZMQMessage(Seq(Frame(bytes)))
  def apply(message: Message): ZMQMessage = ZMQMessage(message.toByteArray)
}

case class Linger(value: Long) extends SocketOption
object Linger extends SocketOptionQuery

case class ReconnectIVL(value: Long) extends SocketOption
object ReconnectIVL extends SocketOptionQuery

case class Backlog(value: Long) extends SocketOption
object Backlog extends SocketOptionQuery

case class ReconnectIVLMax(value: Long) extends SocketOption
object ReconnectIVLMax extends SocketOptionQuery

case class MaxMsgSize(value: Long) extends SocketOption
object MaxMsgSize extends SocketOptionQuery

case class SndHWM(value: Long) extends SocketOption
object SndHWM extends SocketOptionQuery

case class RcvHWM(value: Long) extends SocketOption
object RcvHWM extends SocketOptionQuery

case class HWM(value: Long) extends SocketOption

case class Swap(value: Long) extends SocketOption
object Swap extends SocketOptionQuery

case class Affinity(value: Long) extends SocketOption
object Affinity extends SocketOptionQuery

case class Identity(value: Array[Byte]) extends SocketOption
object Identity extends SocketOptionQuery

case class Rate(value: Long) extends SocketOption
object Rate extends SocketOptionQuery

case class RecoveryInterval(value: Long) extends SocketOption
object RecoveryInterval extends SocketOptionQuery

case class MulticastLoop(value: Boolean) extends SocketOption
object MulticastLoop extends SocketOptionQuery

case class MulticastHops(value: Long) extends SocketOption
object MulticastHops extends SocketOptionQuery

case class ReceiveTimeOut(value: Long) extends SocketOption
object ReceiveTimeOut extends SocketOptionQuery

case class SendTimeOut(value: Long) extends SocketOption
object SendTimeOut extends SocketOptionQuery

case class SendBufferSize(value: Long) extends SocketOption
object SendBufferSize extends SocketOptionQuery

case class ReceiveBufferSize(value: Long) extends SocketOption
object ReceiveBufferSize extends SocketOptionQuery

object ReceiveMore extends SocketOptionQuery
object FileDescriptor extends SocketOptionQuery
