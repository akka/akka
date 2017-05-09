package akka;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.assertNotNull;

public class MinimalTest extends JUnitSuite {
  @Test
  public void testResetConnection() throws IOException, InterruptedException {
    Selector selector = SelectorProvider.provider().openSelector();

    ServerSocketChannel serverSocketChannel = openServerSocket(selector);
    ServerSocket serverSocket = serverSocketChannel.socket();


    System.out.println("Bound to " + serverSocket.getLocalSocketAddress());
    SocketChannel client = openClientSocket(serverSocket.getLocalSocketAddress());
    SelectionKey clientKey = client.register(selector, 0);
    clientKey.interestOps(SelectionKey.OP_READ);

    System.out.println("Starting selector thread");
    monitorSelector(selector);
    System.out.println("Started selector thread");

    SocketChannel socketChannel = serverSocketChannel.accept();
    assertNotNull(socketChannel);
    socketChannel.configureBlocking(false);
    System.out.println("Accepted");


    System.out.println("Registered");
    //writeData(serverSocketChannel);
    abort(socketChannel);
	System.out.println("Closed");
    Thread.sleep(100);
  }

  private void writeData(SocketChannel channel) throws IOException, InterruptedException {
    ByteBuffer buffer = ByteBuffer.allocate( 1024 );

    for (int i=0; i<5; ++i) {
      buffer.put((byte) 0x42);
    }
    buffer.flip();
    channel.write(buffer);
    Thread.sleep(100);
  }

  private void abort(SocketChannel client) throws IOException {
    client.socket().setSoLinger(true, 0);
    client.close();
  }

  private void monitorSelector(Selector selector) {
    new Thread(() -> {
      try {
        while (true) {
          //System.out.println("Selecting...");
          if (selector.select() > 0) {
            Set selectedKeys = selector.selectedKeys();
            Iterator it = selectedKeys.iterator();

            while (it.hasNext()) {
              SelectionKey key = (SelectionKey)it.next();
              System.out.println("Selected " + key.readyOps());
              if ((key.readyOps() & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT) {
              }
            }
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }).start();
  }

  private SocketChannel openClientSocket(SocketAddress serverAddress) throws IOException {
    SocketChannel socketChannel = SocketChannel.open();
    socketChannel.configureBlocking(false);

    socketChannel.connect(serverAddress);
    while (!socketChannel.finishConnect()) {
      // Busy wait
      System.out.println("Connecting");
    }
    return socketChannel;
  }

  private ServerSocketChannel openServerSocket(Selector selector) throws IOException {
    ServerSocketChannel channel = ServerSocketChannel.open();
    channel.configureBlocking(false);

//    channel.register(selector, 0);
    ServerSocket socket = channel.socket();
    socket.bind(new InetSocketAddress("127.0.0.1", 0), 100);
    System.out.println(socket.getLocalSocketAddress());
    return channel;
  }
}
