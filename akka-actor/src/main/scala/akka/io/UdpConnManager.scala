/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.Props
import akka.io.IO.SelectorBasedManager
import akka.io.UdpConn.Connect

class UdpConnManager(udpConn: UdpConnExt) extends SelectorBasedManager(udpConn.settings, udpConn.settings.NrOfSelectors) {

  def receive = workerForCommand {
    case c: Connect ⇒
      val commander = sender
      Props(new UdpConnection(udpConn, commander, c))
  }

}
