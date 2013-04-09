/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import scala.collection.immutable
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import akka.actor.{ Terminated, ActorSystem, ActorRef }
import akka.testkit.TestProbe

object TestUtils {

  def temporaryServerAddress(address: String = "127.0.0.1"): InetSocketAddress =
    temporaryServerAddresses(1, address).head

  def temporaryServerAddresses(numberOfAddresses: Int, hostname: String = "127.0.0.1"): immutable.IndexedSeq[InetSocketAddress] = {
    val sockets = for (_ ← 1 to numberOfAddresses) yield {
      val serverSocket = ServerSocketChannel.open()
      serverSocket.socket.bind(new InetSocketAddress(hostname, 0))
      val port = serverSocket.socket.getLocalPort
      (serverSocket, new InetSocketAddress(hostname, port))
    }

    sockets collect { case (socket, address) ⇒ socket.close(); address }
  }

  def verifyActorTermination(actor: ActorRef)(implicit system: ActorSystem): Unit = {
    val watcher = TestProbe()
    watcher.watch(actor)
    watcher.expectTerminated(actor)
  }

}
