package akka.remote

import akka.actor.Address
import testconductor.{ TestConductorProtocol ⇒ TCP }

package object testconductor {

  implicit def address2proto(addr: Address): TCP.Address =
    TCP.Address.newBuilder
      .setProtocol(addr.protocol)
      .setSystem(addr.system)
      .setHost(addr.host.get)
      .setPort(addr.port.get)
      .build

  implicit def address2scala(addr: TCP.Address): Address =
    Address(addr.getProtocol, addr.getSystem, addr.getHost, addr.getPort)

}