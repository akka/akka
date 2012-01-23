/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

/**
 * A single message frame of a zeromq message
 * @param payload
 */
case class Frame(payload: Seq[Byte])

/**
 * Deserializes ZeroMQ messages into an immutable sequence of frames
 */
class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: Seq[Frame]) = ZMQMessage(frames)
}
