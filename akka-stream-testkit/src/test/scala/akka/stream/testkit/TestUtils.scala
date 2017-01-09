/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.testkit

import scala.collection.immutable
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.DatagramSocket
import java.net.ServerSocket

object TestUtils { // FIXME: remove once going back to project dependencies
  trait GeneralSocket extends Any {
    def bind(sa: SocketAddress): Unit
    def close(): Unit
    def getLocalPort: Int
  }
  implicit class GeneralDatagramSocket(val s: DatagramSocket) extends GeneralSocket {
    def bind(sa: SocketAddress): Unit = s.bind(sa)
    def close(): Unit = s.close()
    def getLocalPort: Int = s.getLocalPort
  }
  implicit class GeneralServerSocket(val s: ServerSocket) extends GeneralSocket {
    def bind(sa: SocketAddress): Unit = s.bind(sa)
    def close(): Unit = s.close()
    def getLocalPort: Int = s.getLocalPort
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
