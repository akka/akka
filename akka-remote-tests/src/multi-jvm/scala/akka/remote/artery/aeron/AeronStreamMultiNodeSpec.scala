/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.aeron

import akka.remote.artery.UdpPortActor
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }

abstract class AeronStreamMultiNodeSpec(config: MultiNodeConfig) extends MultiNodeSpec(config) {

  def channel(roleName: RoleName) = {
    val n = node(roleName)
    val port = MultiNodeSpec.udpPort match {
      case None =>
        system.actorSelection(n / "user" / "updPort") ! UdpPortActor.GetUdpPort
        expectMsgType[Int]
      case Some(p) => p
    }
    s"aeron:udp?endpoint=${n.address.host.get}:$port"
  }
}
