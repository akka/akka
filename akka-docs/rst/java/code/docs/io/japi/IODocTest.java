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
import akka.actor.UntypedActor;
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
  public class Server extends UntypedActor {
    
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
      tcp.tell(TcpMessage.bind(getSelf(),
          new InetSocketAddress("localhost", 0), 100), getSelf());
    }

    @Override
    public void onReceive(Object msg) throws Exception {
      if (msg instanceof Bound) {
        manager.tell(msg, getSelf());

      } else if (msg instanceof CommandFailed) {
        getContext().stop(getSelf());
      
      } else if (msg instanceof Connected) {
        final Connected conn = (Connected) msg;
        manager.tell(conn, getSelf());
        final ActorRef handler = getContext().actorOf(
            Props.create(SimplisticHandler.class));
        getSender().tell(TcpMessage.register(handler), getSelf());
      }
    }
    
  }
  //#server

  static
  //#simplistic-handler
  public class SimplisticHandler extends UntypedActor {
    @Override
    public void onReceive(Object msg) throws Exception {
      if (msg instanceof Received) {
        final ByteString data = ((Received) msg).data();
        System.out.println(data);
        getSender().tell(TcpMessage.write(data), getSelf());
      } else if (msg instanceof ConnectionClosed) {
        getContext().stop(getSelf());
      }
    }
  }
  //#simplistic-handler
  
  static
  //#client
  public class Client extends UntypedActor {
    
    final InetSocketAddress remote;
    final ActorRef listener;
    
    public static Props props(InetSocketAddress remote, ActorRef listener) {
        return Props.create(Client.class, remote, listener);
    }

    public Client(InetSocketAddress remote, ActorRef listener) {
      this.remote = remote;
      this.listener = listener;
      
      final ActorRef tcp = Tcp.get(getContext().system()).manager();
      tcp.tell(TcpMessage.connect(remote), getSelf());
    }

    @Override
    public void onReceive(Object msg) throws Exception {
      if (msg instanceof CommandFailed) {
        listener.tell("failed", getSelf());
        getContext().stop(getSelf());
        
      } else if (msg instanceof Connected) {
        listener.tell(msg, getSelf());
        getSender().tell(TcpMessage.register(getSelf()), getSelf());
        getContext().become(connected(getSender()));
      }
    }

    private Procedure<Object> connected(final ActorRef connection) {
      return new Procedure<Object>() {
        @Override
        public void apply(Object msg) throws Exception {
          
          if (msg instanceof ByteString) {
            connection.tell(TcpMessage.write((ByteString) msg), getSelf());
        
          } else if (msg instanceof CommandFailed) {
            // OS kernel socket buffer was full
          
          } else if (msg instanceof Received) {
            listener.tell(((Received) msg).data(), getSelf());
          
          } else if (msg.equals("close")) {
            connection.tell(TcpMessage.close(), getSelf());
          
          } else if (msg instanceof ConnectionClosed) {
            getContext().stop(getSelf());
          }
        }
      };
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
