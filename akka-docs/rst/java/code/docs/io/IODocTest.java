/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io;

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
import akka.io.Tcp;
import akka.io.TcpExt;
import akka.io.TcpMessage;
import akka.io.TcpSO;
import akka.util.ByteString;
//#imports

public class IODocTest {

  static public class Demo extends UntypedActor {
    ActorRef connectionActor = null;
    ActorRef listener = getSelf();

    @Override
    public void onReceive(Object msg) {
      if ("connect".equals(msg)) {
        //#manager
        final ActorRef tcp = Tcp.get(system).manager();
        //#manager
        //#connect
        final InetSocketAddress remoteAddr = new InetSocketAddress("127.0.0.1",
            12345);
        tcp.tell(TcpMessage.connect(remoteAddr), getSelf());
        //#connect
        //#connect-with-options
        final InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1",
            1234);
        final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
        options.add(TcpSO.keepAlive(true));
        tcp.tell(TcpMessage.connect(remoteAddr, localAddr, options, null, false), getSelf());
        //#connect-with-options
      } else
      //#connected
      if (msg instanceof Tcp.Connected) {
        final Tcp.Connected conn = (Tcp.Connected) msg;
        connectionActor = getSender();
        connectionActor.tell(TcpMessage.register(listener), getSelf());
      }
      //#connected
      else
      //#received
      if (msg instanceof Tcp.Received) {
        final Tcp.Received recv = (Tcp.Received) msg;
        final ByteString data = recv.data();
        // and do something with the received data ...
      } else if (msg instanceof Tcp.CommandFailed) {
        final Tcp.CommandFailed failed = (Tcp.CommandFailed) msg;
        final Tcp.Command command = failed.cmd();
        // react to failed connect, bind, write, etc.
      } else if (msg instanceof Tcp.ConnectionClosed) {
        final Tcp.ConnectionClosed closed = (Tcp.ConnectionClosed) msg;
        if (closed.isAborted()) {
          // handle close reasons like this
        }
      }
      //#received
      else
      if ("bind".equals(msg)) {
        final ActorRef handler = getSelf();
        //#bind
        final ActorRef tcp = Tcp.get(system).manager();
        final InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1",
            1234);
        final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
        options.add(TcpSO.reuseAddress(true));
        tcp.tell(TcpMessage.bind(handler, localAddr, 10, options, false), getSelf());
        //#bind
      }
    }
  }

  static ActorSystem system;

  // This is currently only a compilation test, nothing is run
}
