/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

case class Frame(payload: Seq[Byte])
object Frame { def apply(s: String): Frame = Frame(s.getBytes) }

class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: Seq[Frame]) = ZMQMessage(frames)
}
