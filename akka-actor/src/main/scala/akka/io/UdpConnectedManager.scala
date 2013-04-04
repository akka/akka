/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.Props
import akka.io.IO.SelectorBasedManager
import akka.io.UdpConnected.Connect

/**
 * INTERNAL API
 */
private[io] class UdpConnectedManager(udpConn: UdpConnectedExt) extends SelectorBasedManager(udpConn.settings, udpConn.settings.NrOfSelectors) {

  def receive = workerForCommandHandler {
    case c: Connect ⇒
      val commander = sender
      Props(new UdpConnectedection(udpConn, commander, c))
  }

}
