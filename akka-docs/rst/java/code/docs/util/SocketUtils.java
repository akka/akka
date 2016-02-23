/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.util;

import java.net.InetSocketAddress;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;

public class SocketUtils {

  public static InetSocketAddress temporaryServerAddress(String hostname) {
    try {
      ServerSocket socket = ServerSocketChannel.open().socket();
      socket.bind(new InetSocketAddress(hostname, 0));
      InetSocketAddress address = new InetSocketAddress(hostname, socket.getLocalPort());
      socket.close();
      return address;
    }
    catch (IOException io) {
      throw new RuntimeException(io);
    }
  }

  public static InetSocketAddress temporaryServerAddress() {
    return temporaryServerAddress("127.0.0.1");
  }

}