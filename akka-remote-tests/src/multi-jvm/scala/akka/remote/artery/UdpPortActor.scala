/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.actor.Actor
import akka.remote.RARP
import akka.testkit.SocketUtil

object UdpPortActor {
  case object GetUdpPort
}

/**
 * Used for exchanging free udp port between multi-jvm nodes
 */
class UdpPortActor extends Actor {
  import UdpPortActor._

  val port = SocketUtil.temporaryServerAddress(RARP(context.system).provider
    .getDefaultAddress.host.get, udp = true).getPort

  def receive = {
    case GetUdpPort ⇒ sender() ! port
  }
}
