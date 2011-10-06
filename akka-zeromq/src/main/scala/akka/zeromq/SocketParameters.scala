/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import akka.actor.ActorRef

abstract class Direction
case object Bind extends Direction
case object Connect extends Direction

class SocketParameters(
  val endpoint: String,
  val direction: Direction,
  val listener: Option[ActorRef] = None,
  val synchronizedSending: Boolean = false,
  val deserializer: Deserializer = new ZMQMessageDeserializer
)
