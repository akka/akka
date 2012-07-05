/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import com.google.protobuf.Message

sealed trait Request

case class Connect(endpoint: String) extends Request
case class Bind(endpoint: String) extends Request
private[zeromq] case object Close extends Request

case class Subscribe(payload: Seq[Byte]) extends Request
object Subscribe {
  def apply(topic: String): Subscribe = {
    Subscribe(topic.getBytes)
  }
}

case class Unsubscribe(payload: Seq[Byte]) extends Request
object Unsubscribe {
  def apply(topic: String): Unsubscribe = {
    Unsubscribe(topic.getBytes)
  }
}

case class Send(frames: Seq[Frame]) extends Request

case class ZMQMessage(frames: Seq[Frame]) {
  def firstFrameAsString = {
    new String(frames.head.payload.toArray)
  }
}
object ZMQMessage { 
  def apply(bytes: Array[Byte]): ZMQMessage = {
    ZMQMessage(Seq(Frame(bytes)))
  }
  def apply(message: Message): ZMQMessage = {
    ZMQMessage(message.toByteArray)
  }
}
