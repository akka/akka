/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import java.io.{ FileOutputStream, File }
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

object TestUtils {
  def writeAllText(text: String, file: File): Unit = {
    val fos = new FileOutputStream(file)
    try {
      fos.write(text.getBytes("UTF-8"))
    } finally fos.close()
  }

  // TODO duplicated code from akka-http-core-tests
  def temporaryServerAddress(interface: String = "127.0.0.1"): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.socket.bind(new InetSocketAddress(interface, 0))
      val port = serverSocket.socket.getLocalPort
      new InetSocketAddress(interface, port)
    } finally serverSocket.close()
  }

  // TODO duplicated code from akka-http-core-tests
  def temporaryServerHostnameAndPort(interface: String = "127.0.0.1"): (InetSocketAddress, String, Int) = {
    val socketAddress = temporaryServerAddress(interface)
    (socketAddress, socketAddress.getHostName, socketAddress.getPort)
  }
}
