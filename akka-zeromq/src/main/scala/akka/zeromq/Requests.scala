/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import com.google.protobuf.Message

sealed trait Request
sealed trait SocketOption extends Request
sealed trait SocketOptionQuery extends Request

case class Connect(endpoint: String) extends Request
case class Bind(endpoint: String) extends Request
private[zeromq] case object Close extends Request

case class Subscribe(payload: Seq[Byte]) extends Request
object Subscribe {
  def apply(topic: String): Subscribe = Subscribe(topic.getBytes)
}

case class Unsubscribe(payload: Seq[Byte]) extends Request
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
