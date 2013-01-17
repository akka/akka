package akka.io

import java.nio.channels.ServerSocketChannel
import java.net.InetSocketAddress

object TemporaryServerAddress {
  def get(address: String): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.socket.bind(new InetSocketAddress(address, 0))
    val port = serverSocket.socket.getLocalPort
    serverSocket.close()
    new InetSocketAddress(address, port)
  }
}
