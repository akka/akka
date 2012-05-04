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

  implicit def direction2proto(dir: Direction): TCP.Direction = dir match {
    case Direction.Send    ⇒ TCP.Direction.Send
    case Direction.Receive ⇒ TCP.Direction.Receive
    case Direction.Both    ⇒ TCP.Direction.Both
  }

  implicit def direction2scala(dir: TCP.Direction): Direction = dir match {
    case TCP.Direction.Send    ⇒ Direction.Send
    case TCP.Direction.Receive ⇒ Direction.Receive
    case TCP.Direction.Both    ⇒ Direction.Both
  }

}