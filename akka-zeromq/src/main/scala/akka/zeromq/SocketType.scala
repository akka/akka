/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.zeromq

import org.zeromq.{ ZMQ ⇒ JZMQ }

object SocketType extends Enumeration {
  type SocketType = Value
  val Pub = Value(JZMQ.PUB)
  val Sub = Value(JZMQ.SUB)
  val Dealer = Value(JZMQ.DEALER)
  val Router = Value(JZMQ.ROUTER)
}
