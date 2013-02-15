/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
//#imports
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import akka.actor.ActorRef;
import akka.io.Inet;
import akka.io.UdpConn;
import akka.io.UdpConnMessage;
import akka.io.UdpSO;
import akka.util.ByteString;
//#imports

public class UdpConnDocTest {

  static public class Demo extends UntypedActor {
    ActorRef connectionActor = null;
    ActorRef handler = getSelf();

    @Override
    public void onReceive(Object msg) {
      if ("connect".equals(msg)) {
        //#manager
        final ActorRef udp = UdpConn.get(system).manager();
        //#manager
        //#connect
        final InetSocketAddress remoteAddr =
            new InetSocketAddress("127.0.0.1", 12345);
        udp.tell(UdpConnMessage.connect(handler, remoteAddr), getSelf());
        // or with socket options
        final InetSocketAddress localAddr =
            new InetSocketAddress("127.0.0.1", 1234);
        final List<Inet.SocketOption> options =
            new ArrayList<Inet.SocketOption>();
        options.add(UdpSO.broadcast(true));
        udp.tell(UdpConnMessage.connect(handler, remoteAddr, localAddr, options), getSelf());
        //#connect
      } else
        //#connected
        if (msg instanceof UdpConn.Connected) {
          final UdpConn.Connected conn = (UdpConn.Connected) msg;
          connectionActor = getSender(); // Save the worker ref for later use
        }
        //#connected
        else
          //#received
          if (msg instanceof UdpConn.Received) {
            final UdpConn.Received recv = (UdpConn.Received) msg;
            final ByteString data = recv.data();
            // and do something with the received data ...
          } else if (msg instanceof UdpConn.CommandFailed) {
            final UdpConn.CommandFailed failed = (UdpConn.CommandFailed) msg;
            final UdpConn.Command command = failed.cmd();
            // react to failed connect, etc.
          } else if (msg instanceof UdpConn.Disconnected) {
            // do something on disconnect
          }
          //#received
          else
          if ("send".equals(msg)) {
            ByteString data = ByteString.empty();
            //#send
            connectionActor.tell(UdpConnMessage.send(data), getSelf());
            //#send
          }
    }
  }

  static ActorSystem system;

  @BeforeClass
  static public void setup() {
    system = ActorSystem.create("UdpConnDocTest");
  }

  @AfterClass
  static public void teardown() {
    system.shutdown();
  }

  @Test
  public void demonstrateConnect() {
  }

}
