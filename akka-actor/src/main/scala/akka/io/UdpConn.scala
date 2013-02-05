/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ExtendedActorSystem, Props, ActorSystemImpl, ExtensionKey }

object UdpConn extends ExtensionKey[UdpConnExt] {

}

class UdpConnExt(system: ExtendedActorSystem) extends IO.Extension {

  val manager = {
    system.asInstanceOf[ActorSystemImpl].systemActorOf(
      props = Props.empty,
      name = "IO-UDP-CONN")
  }

}

