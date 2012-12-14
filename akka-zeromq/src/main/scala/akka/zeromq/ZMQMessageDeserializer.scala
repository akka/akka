/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import scala.collection.immutable
import akka.util.ByteString

/**
 * Deserializes ZeroMQ messages into an immutable sequence of frames
 */
class ZMQMessageDeserializer extends Deserializer {
  def apply(frames: immutable.Seq[ByteString]): ZMQMessage = ZMQMessage(frames)
}
