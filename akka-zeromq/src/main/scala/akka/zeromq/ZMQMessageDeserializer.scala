/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

/**
 * A single message frame of a zeromq message
 * @param payload
 */
case class Frame(payload: Seq[Byte])
object Frame { def apply(s: String): Frame = Frame(s.getBytes) }

/**
 * Deserializes ZeroMQ messages into an immutable sequence of frames
 */
class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: Seq[Frame]) = ZMQMessage(frames)
}
