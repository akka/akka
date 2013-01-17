/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.ServerSocketChannel
import java.net.InetSocketAddress

object TemporaryServerAddress {

  def apply(address: String = "127.0.0.1"): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress(address, 0))
    val port = serverSocket.socket.getLocalPort
    serverSocket.close()
    new InetSocketAddress(address, port)
  }
}
