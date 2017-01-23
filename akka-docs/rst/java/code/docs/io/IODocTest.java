/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io;

import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.actor.AbstractActor;
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

  static public class Demo extends AbstractActor {
    ActorRef connectionActor = null;
    ActorRef listener = self();

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .matchEquals("connect", msg -> {
          //#manager
          final ActorRef tcp = Tcp.get(system).manager();
          //#manager
          //#connect
          final InetSocketAddress remoteAddr = new InetSocketAddress("127.0.0.1",
              12345);
          tcp.tell(TcpMessage.connect(remoteAddr), self());
          //#connect
          //#connect-with-options
          final InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1",
              1234);
          final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
          options.add(TcpSO.keepAlive(true));
          tcp.tell(TcpMessage.connect(remoteAddr, localAddr, options, null, false), self());
          //#connect-with-options
        })
        //#connected
        .match(Tcp.Connected.class, conn -> {
          connectionActor = sender();
          connectionActor.tell(TcpMessage.register(listener), self());
        })
        //#connected
        //#received
        .match(Tcp.Received.class, recv -> {
          final ByteString data = recv.data();
          // and do something with the received data ...
        })
        .match(Tcp.CommandFailed.class, failed -> {
          final Tcp.Command command = failed.cmd();
          // react to failed connect, bind, write, etc.
        })
        .match(Tcp.ConnectionClosed.class, closed -> {
          if (closed.isAborted()) {
            // handle close reasons like this
          }
        })
        //#received
        .matchEquals("bind", msg -> {
          final ActorRef handler = self();
          //#bind
          final ActorRef tcp = Tcp.get(system).manager();
          final InetSocketAddress localAddr = new InetSocketAddress("127.0.0.1",
              1234);
          final List<Inet.SocketOption> options = new ArrayList<Inet.SocketOption>();
          options.add(TcpSO.reuseAddress(true));
          tcp.tell(TcpMessage.bind(handler, localAddr, 10, options, false), self());
          //#bind
        })
        .build();
    }
  }

  static ActorSystem system;

  // This is currently only a compilation test, nothing is run
}
