/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

object Frame {
  def apply(text: String): Frame = new Frame(text)
}

/**
 * A single message frame of a zeromq message
 * @param payload
 */
case class Frame(payload: Seq[Byte]) {
  def this(bytes: Array[Byte]) = this(bytes.toSeq)
  def this(text: String) = this(text.getBytes("UTF-8"))
}

/**
 * Deserializes ZeroMQ messages into an immutable sequence of frames
 */
class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: Seq[Frame]) = ZMQMessage(frames)
}
