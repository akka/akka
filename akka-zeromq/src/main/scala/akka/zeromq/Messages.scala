/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

sealed trait AbstractMessage 
case object Ok extends AbstractMessage
case object PollerRequest extends AbstractMessage
case object Start extends AbstractMessage
case class Subscribe(payload: Array[Byte])
case class Unsubscribe(payload: Array[Byte])
case class SocketRequest(socketType: Int) extends AbstractMessage
case class ZMQMessage(val frames: Array[Frame]) extends AbstractMessage
object ZMQMessage { def apply(bytes: Array[Byte]): ZMQMessage = ZMQMessage(Array(Frame(bytes))) }
