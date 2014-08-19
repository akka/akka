/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io

import java.net.{InetAddress, InetSocketAddress, NetworkInterface, StandardProtocolFamily}
import java.nio.channels.DatagramChannel

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.io.Inet.{DatagramChannelCreator, SocketOption}
import akka.io.{IO, Udp}
import akka.util.ByteString

//#inet6-protocol-family
final case class Inet6ProtocolFamily() extends DatagramChannelCreator {
  override def create() =
    DatagramChannel.open(StandardProtocolFamily.INET6)
}
//#inet6-protocol-family

//#multicast-group
final case class MulticastGroup(address: String, interface: String) extends SocketOption {
  override def afterConnect(c: DatagramChannel) {
    val group = InetAddress.getByName(address)
    val networkInterface = NetworkInterface.getByName(interface)
    c.join(group, networkInterface)
  }
}
//#multicast-group

class Listener(iface: String, group: String, port: Int, sink: ActorRef) extends Actor with ActorLogging {
  //#bind
  import context.system
  val opts = List(Inet6ProtocolFamily(), MulticastGroup(group, iface))
  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(port), opts)
  //#bind

  def receive = {
    case Udp.Bound(to) => log.info(s"Bound to $to")
    case Udp.Received(data, remote) =>
      val msg = data.decodeString("utf-8")
      log.info(s"Received '$msg' from '$remote'")
      sink ! msg
  }
}

class Sender(group: String, port: Int, msg: String) extends Actor with ActorLogging {
  import context.system
  IO(Udp) ! Udp.SimpleSender(List(Inet6ProtocolFamily()))

  def receive = {
    case Udp.SimpleSenderReady => {
      val remote = new InetSocketAddress(group, port)
      log.info(s"Sending message to $remote")
      sender() ! Udp.Send(ByteString(msg), remote)
    }
  }
}
