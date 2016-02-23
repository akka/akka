/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import scala.collection.immutable
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel

/**
 * Utilities to get free socket address.
 */
object SocketUtil {

  import scala.language.reflectiveCalls

  // Structural type needed since DatagramSocket and ServerSocket has no common ancestor apart from Object
  private type GeneralSocket = {
    def bind(sa: SocketAddress): Unit
    def close(): Unit
    def getLocalPort(): Int
  }

  def temporaryServerAddress(address: String = "127.0.0.1", udp: Boolean = false): InetSocketAddress =
    temporaryServerAddresses(1, address, udp).head

  def temporaryServerAddresses(numberOfAddresses: Int, hostname: String = "127.0.0.1", udp: Boolean = false): immutable.IndexedSeq[InetSocketAddress] = {
    Vector.fill(numberOfAddresses) {
      val serverSocket: GeneralSocket =
        if (udp) DatagramChannel.open().socket()
        else ServerSocketChannel.open().socket()

      serverSocket.bind(new InetSocketAddress(hostname, 0))
      (serverSocket, new InetSocketAddress(hostname, serverSocket.getLocalPort))
    } collect { case (socket, address) ⇒ socket.close(); address }
  }

}
