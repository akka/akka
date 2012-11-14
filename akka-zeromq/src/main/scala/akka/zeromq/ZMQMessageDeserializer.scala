/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import scala.collection.immutable

object Frame {
  def apply(bytes: Array[Byte]): Frame = new Frame(bytes)
  def apply(text: String): Frame = new Frame(text)
}

/**
 * A single message frame of a zeromq message
 * @param payload
 */
case class Frame(payload: immutable.Seq[Byte]) {
  def this(bytes: Array[Byte]) = this(bytes.to[immutable.Seq])
  def this(text: String) = this(text.getBytes("UTF-8"))
}

/**
 * Deserializes ZeroMQ messages into an immutable sequence of frames
 */
class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: immutable.Seq[Frame]): ZMQMessage = ZMQMessage(frames)
}
