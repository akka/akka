/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import scala.collection.immutable
import java.net.{ SocketAddress, ServerSocket, DatagramSocket, InetSocketAddress }
import java.nio.channels.{ DatagramChannel, ServerSocketChannel }
import akka.actor.{ Terminated, ActorSystem, ActorRef }
import akka.testkit.TestProbe

object TestUtils {

  // Structural type needed since DatagramSocket and ServerSocket has no common ancestor apart from Object
  type GeneralSocket = {
    def bind(sa: SocketAddress): Unit
    def close(): Unit
    def getLocalPort(): Int
  }

  def temporaryServerAddress(address: String = "127.0.0.1", udp: Boolean = false): InetSocketAddress =
    temporaryServerAddresses(1, address, udp).head

  def temporaryServerAddresses(numberOfAddresses: Int, hostname: String = "127.0.0.1", udp: Boolean = false): immutable.IndexedSeq[InetSocketAddress] = {
    val sockets = for (_ ← 1 to numberOfAddresses) yield {
      val serverSocket: GeneralSocket =
        if (udp) DatagramChannel.open().socket()
        else ServerSocketChannel.open().socket()

      serverSocket.bind(new InetSocketAddress(hostname, 0))
      (serverSocket, new InetSocketAddress(hostname, serverSocket.getLocalPort))
    }

    sockets collect { case (socket, address) ⇒ socket.close(); address }
  }

  def verifyActorTermination(actor: ActorRef)(implicit system: ActorSystem): Unit = {
    val watcher = TestProbe()
    watcher.watch(actor)
    watcher.expectTerminated(actor)
  }

}
