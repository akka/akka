/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.{ZMQ => JZMQ}

object SocketOptionType extends Enumeration {
  type SocketOptionType = Value
  val Linger = Value(1)
}
