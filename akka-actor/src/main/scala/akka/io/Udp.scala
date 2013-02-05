/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.DatagramSocket
import akka.io.Inet.SocketOption

object Udp {

  object SO {

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_BROADCAST option
     *
     * For more information see [[java.net.DatagramSocket#setBroadcast]]
     */
    case class Broadcast(on: Boolean) extends SocketOption {
      override def beforeDatagramBind(s: DatagramSocket): Unit = s.setBroadcast(on)
    }

  }

}
