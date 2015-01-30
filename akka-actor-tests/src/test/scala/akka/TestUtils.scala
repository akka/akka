/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import scala.collection.immutable
import scala.concurrent.duration.Duration
import java.net.{ SocketAddress, InetSocketAddress }
import java.nio.channels.{ DatagramChannel, ServerSocketChannel }
import akka.actor.{ ActorSystem, ActorRef }
import akka.testkit.TestProbe

import language.reflectiveCalls

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
    Vector.fill(numberOfAddresses) {
      val serverSocket: GeneralSocket =
        if (udp) DatagramChannel.open().socket()
        else ServerSocketChannel.open().socket()

      serverSocket.bind(new InetSocketAddress(hostname, 0))
      (serverSocket, new InetSocketAddress(hostname, serverSocket.getLocalPort))
    } collect { case (socket, address) ⇒ socket.close(); address }
  }

  def verifyActorTermination(actor: ActorRef, max: Duration = Duration.Undefined)(implicit system: ActorSystem): Unit = {
    val watcher = TestProbe()
    watcher.watch(actor)
    watcher.expectTerminated(actor, max)
  }

}
