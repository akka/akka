/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io.japi;


import akka.testkit.AkkaJUnitActorSystemResource;
import docs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;

//#imports
import java.net.InetSocketAddress;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.io.Tcp;
import akka.io.Tcp.Bound;
import akka.io.Tcp.CommandFailed;
import akka.io.Tcp.Connected;
import akka.io.Tcp.ConnectionClosed;
import akka.io.Tcp.Received;
import akka.io.TcpMessage;
import akka.japi.Procedure;
import akka.util.ByteString;
//#imports

import akka.testkit.JavaTestKit;
import akka.testkit.AkkaSpec;

public class IODocTest extends AbstractJavaTest {

  static
  //#server
  public class Server extends AbstractActor {
    
    final ActorRef manager;
    
    public Server(ActorRef manager) {
      this.manager = manager;
    }
    
    public static Props props(ActorRef manager) {
      return Props.create(Server.class, manager);
    }

    @Override
    public void preStart() throws Exception {
      final ActorRef tcp = Tcp.get(getContext().system()).manager();
      tcp.tell(TcpMessage.bind(self(),
          new InetSocketAddress("localhost", 0), 100), self());
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Bound.class, msg -> {
          manager.tell(msg, self());
  
        })
        .match(CommandFailed.class, msg -> {
          getContext().stop(self());
        
        })
        .match(Connected.class, conn -> {
          manager.tell(conn, self());
          final ActorRef handler = getContext().actorOf(
              Props.create(SimplisticHandler.class));
          sender().tell(TcpMessage.register(handler), self());
        })
        .build();
    }
    
  }
  //#server

  static
  //#simplistic-handler
  public class SimplisticHandler extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Received.class, msg -> {
          final ByteString data = msg.data();
          System.out.println(data);
          sender().tell(TcpMessage.write(data), self());
        })
        .match(ConnectionClosed.class, msg -> {
          getContext().stop(self());
        })
        .build();
    }
  }
  //#simplistic-handler
  
  static
  //#client
  public class Client extends AbstractActor {
    
    final InetSocketAddress remote;
    final ActorRef listener;
    
    public static Props props(InetSocketAddress remote, ActorRef listener) {
        return Props.create(Client.class, remote, listener);
    }

    public Client(InetSocketAddress remote, ActorRef listener) {
      this.remote = remote;
      this.listener = listener;
      
      final ActorRef tcp = Tcp.get(getContext().system()).manager();
      tcp.tell(TcpMessage.connect(remote), self());
    }

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(CommandFailed.class, msg -> {
          listener.tell("failed", self());
          getContext().stop(self());
          
        })
        .match(Connected.class, msg -> {
          listener.tell(msg, self());
          sender().tell(TcpMessage.register(self()), self());
          getContext().become(connected(sender()));
        })
        .build();
    }

    private Receive connected(final ActorRef connection) {
      return receiveBuilder()
        .match(ByteString.class, msg -> {
            connection.tell(TcpMessage.write((ByteString) msg), self());
        })
        .match(CommandFailed.class, msg -> {
          // OS kernel socket buffer was full
        })
        .match(Received.class, msg -> {
          listener.tell(msg.data(), self());
        })
        .matchEquals("close", msg -> {
          connection.tell(TcpMessage.close(), self());
        })
        .match(ConnectionClosed.class, msg -> {
          getContext().stop(self());
        })
        .build();
    }
    
  }
  //#client

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("IODocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void testConnection() {
    new JavaTestKit(system) {
      {
        @SuppressWarnings("unused")
        final ActorRef server = system.actorOf(Server.props(getRef()), "server1");
        final InetSocketAddress listen = expectMsgClass(Bound.class).localAddress();
        final ActorRef client = system.actorOf(Client.props(listen, getRef()), "client1");
        
        final Connected c1 = expectMsgClass(Connected.class);
        final Connected c2 = expectMsgClass(Connected.class);
        assert c1.localAddress().equals(c2.remoteAddress());
        assert c2.localAddress().equals(c1.remoteAddress());
        
        client.tell(ByteString.fromString("hello"), getRef());
        final ByteString reply = expectMsgClass(ByteString.class);
        assert reply.utf8String().equals("hello");
        
        watch(client);
        client.tell("close", getRef());
        expectTerminated(client);
      }
    };
  }

}
