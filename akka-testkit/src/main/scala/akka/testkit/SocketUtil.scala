/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import scala.collection.immutable
import scala.util.Random
import java.net.{ DatagramSocket, InetSocketAddress, NetworkInterface, StandardProtocolFamily }
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel

import scala.util.control.NonFatal

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
      case _: java.net.BindException =>
        false
    }
  }

  sealed trait Protocol
  final case object Tcp extends Protocol
  final case object Udp extends Protocol
  final case object Both extends Protocol

  /** @return A port on 'localhost' that is currently available */
  def temporaryLocalPort(udp: Boolean = false): Int = temporaryServerAddress("localhost", udp).getPort

  /**
   * Find a free local post on 'localhost' that is available on the given protocol
   * If both UDP and TCP need to be free specify `Both`
   */
  def temporaryLocalPort(protocol: Protocol): Int = {
    def findBoth(tries: Int): Int = {
      if (tries == 0) {
        throw new RuntimeException("Unable to find a port that is free for tcp and udp")
      }
      val tcpPort = SocketUtil.temporaryLocalPort(udp = false)
      val ds: DatagramSocket = DatagramChannel.open().socket()
      try {
        ds.bind(new InetSocketAddress("localhost", tcpPort))
        tcpPort
      } catch {
        case NonFatal(_) => findBoth(tries - 1)
      } finally {
        ds.close()
      }
    }

    protocol match {
      case Tcp  => temporaryLocalPort(udp = false)
      case Udp  => temporaryLocalPort(udp = true)
      case Both => findBoth(5)
    }
  }

  /**
   * @param address host address. If not set, a loopback IP from the 127.20.0.0/16 range is picked
   * @param udp     if true, select a port that is free for running a UDP server. Otherwise TCP.
   * @return an address (host+port) that is currently available to bind on
   */
  def temporaryServerAddress(address: String = RANDOM_LOOPBACK_ADDRESS, udp: Boolean = false): InetSocketAddress =
    temporaryServerAddresses(1, address, udp).head

  def temporaryServerAddresses(
      numberOfAddresses: Int,
      hostname: String = RANDOM_LOOPBACK_ADDRESS,
      udp: Boolean = false): immutable.IndexedSeq[InetSocketAddress] = {
    Vector
      .fill(numberOfAddresses) {

        val address = hostname match {
          case RANDOM_LOOPBACK_ADDRESS =>
            if (canBindOnAlternativeLoopbackAddresses) s"127.20.${Random.nextInt(256)}.${Random.nextInt(256)}"
            else "127.0.0.1"
          case other =>
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

      }
      .collect { case (socket, address) => socket.close(); address }
  }

  def temporaryServerHostnameAndPort(interface: String = RANDOM_LOOPBACK_ADDRESS): (String, Int) = {
    val socketAddress = temporaryServerAddress(interface)
    socketAddress.getHostString -> socketAddress.getPort
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
