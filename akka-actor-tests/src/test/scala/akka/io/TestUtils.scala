/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import akka.actor.{ Terminated, ActorSystem, ActorRef }
import akka.testkit.TestProbe

object TestUtils {

  def temporaryServerAddress(address: String = "127.0.0.1"): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.socket.bind(new InetSocketAddress(address, 0))
      val port = serverSocket.socket.getLocalPort
      new InetSocketAddress(address, port)
    } finally serverSocket.close()
  }

  def verifyActorTermination(actor: ActorRef)(implicit system: ActorSystem): Unit = {
    val watcher = TestProbe()
    watcher.watch(actor)
    assert(watcher.expectMsgType[Terminated].actor == actor)
  }

}
