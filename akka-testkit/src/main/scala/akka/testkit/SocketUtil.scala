/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import scala.collection.immutable
import scala.util.Random
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.net.NetworkInterface
import java.net.StandardProtocolFamily

/**
 * Utilities to get free socket address.
 */
object SocketUtil {

  val RANDOM_LOOPBACK_ADDRESS = "RANDOM_LOOPBACK_ADDRESS"

  private val canBindOnAlternativeLoopbackAddresses = {
    try {
      SocketUtil.temporaryServerAddress(address = "127.20.0.0")
      true
    } catch {
      case e: java.net.BindException ⇒
        false
    }
  }

  /** @return A port on 'localhost' that is currently available */
  def temporaryLocalPort(udp: Boolean = false): Int = temporaryServerAddress("localhost", udp).getPort

  /**
   * @param address host address. If not set, a loopback IP from the 127.20.0.0/16 range is picked
   * @param udp if true, select a port that is free for running a UDP server. Otherwise TCP.
   * @return an address (host+port) that is currently available to bind on
   */
  def temporaryServerAddress(address: String = RANDOM_LOOPBACK_ADDRESS, udp: Boolean = false): InetSocketAddress =
    temporaryServerAddresses(1, address, udp).head

  def temporaryServerAddresses(numberOfAddresses: Int, hostname: String = RANDOM_LOOPBACK_ADDRESS, udp: Boolean = false): immutable.IndexedSeq[InetSocketAddress] = {
    Vector.fill(numberOfAddresses) {

      val address = hostname match {
        case RANDOM_LOOPBACK_ADDRESS ⇒
          if (canBindOnAlternativeLoopbackAddresses) s"127.20.${Random.nextInt(256)}.${Random.nextInt(256)}"
          else "127.0.0.1"
        case other ⇒
          other
      }

      if (udp) {
        val ds = DatagramChannel.open().socket()
        ds.bind(new InetSocketAddress(address, 0))
        (ds, new InetSocketAddress(address, ds.getLocalPort))
      } else {
        val ss = ServerSocketChannel.open().socket()
        ss.bind(new InetSocketAddress(address, 0))
        (ss, new InetSocketAddress(address, ss.getLocalPort))
      }

    } collect { case (socket, address) ⇒ socket.close(); address }
  }

  def temporaryServerHostnameAndPort(interface: String = RANDOM_LOOPBACK_ADDRESS): (String, Int) = {
    val socketAddress = temporaryServerAddress(interface)
    socketAddress.getHostString → socketAddress.getPort
  }

  def temporaryUdpIpv6Port(iface: NetworkInterface) = {
    val serverSocket = DatagramChannel.open(StandardProtocolFamily.INET6).socket()
    serverSocket.bind(new InetSocketAddress(iface.getInetAddresses.nextElement(), 0))
    val port = serverSocket.getLocalPort
    serverSocket.close()
    port
  }

  def notBoundServerAddress(address: String): InetSocketAddress = new InetSocketAddress(address, 0)
  def notBoundServerAddress(): InetSocketAddress = notBoundServerAddress("127.0.0.1")
}
