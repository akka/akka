/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

case class Frame(payload: Seq[Byte])
object Frame { def apply(s: String): Frame = Frame(s.getBytes) }

trait Deserializer {
  def apply(frames: Seq[Frame]): Any
}

class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: Seq[Frame]) = ZMQMessage(frames)
}
