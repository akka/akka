/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.AbstractPipelineContext;
import akka.io.HasLogging;
import akka.io.SslTlsSupport;
import akka.io.Tcp;
import akka.io.Tcp.Bound;
import akka.io.Tcp.Command;
import akka.io.Tcp.CommandFailed;
import akka.io.Tcp.Connected;
import akka.io.Tcp.Event;
import akka.io.Tcp.Received;
import akka.io.TcpMessage;
import akka.io.TcpPipelineHandler;
import akka.io.TcpPipelineHandler.Init;
import akka.io.ssl.SslTlsSupportSpec;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;

public class SslDocTest {

  static
  //#client
  public class SslClient extends UntypedActor {
    final InetSocketAddress remote;
    final SSLContext sslContext;
    final ActorRef listener;

    final LoggingAdapter log = Logging
        .getLogger(getContext().system(), getSelf());

    public SslClient(InetSocketAddress remote, SSLContext sslContext, ActorRef listener) {
      this.remote = remote;
      this.sslContext = sslContext;
      this.listener = listener;

      // open a connection to the remote TCP port
      Tcp.get(getContext().system()).getManager()
          .tell(TcpMessage.connect(remote), getSelf());
    }
    
    class Context extends AbstractPipelineContext implements HasLogging {
      @Override
      public LoggingAdapter getLogger() {
        return log;
      }
    }
    
    Init<HasLogging, Command, Event> init = null;

    @Override
    public void onReceive(Object msg) {
      if (msg instanceof CommandFailed) {
        getContext().stop(getSelf());
        
      } else if (msg instanceof Connected) {
        // create a javax.net.ssl.SSLEngine for our peer in client mode
        final SSLEngine engine = sslContext.createSSLEngine(
            remote.getHostName(), remote.getPort());
        engine.setUseClientMode(true);
        final SslTlsSupport ssl = new SslTlsSupport(engine);

        // set up the context for communicating with TcpPipelineHandler
        init = new Init<HasLogging, Command, Event>(ssl) {
          @Override
          public HasLogging makeContext(ActorContext ctx) {
            return new Context();
          }
        };
        // create handler for pipeline, setting ourselves as payload recipient
        final ActorRef handler = getContext().actorOf(
            TcpPipelineHandler.create(init, getSender(), getSelf()));
        
        // register the SSL handler with the connection
        getSender().tell(TcpMessage.register(handler), getSelf());
        // and send a message across the SSL channel
        handler.tell(
            init.command(TcpMessage.write(ByteString.fromString("hello"))),
            getSelf());
      
      } else if (msg instanceof Init.Event) {
        // unwrap TcpPipelineHandler’s event into a Tcp.Event
        final Event recv = init.event(msg);
        if (recv instanceof Received) {
          // and inform someone of the received payload
          listener.tell(((Received) recv).data().utf8String(), getSelf());
        }
      }
    }
  }
  //#client

  static
  //#server
  public class SslServer extends UntypedActor {
    final SSLContext sslContext;
    final ActorRef listener;

    final LoggingAdapter log = Logging
        .getLogger(getContext().system(), getSelf());

    public SslServer(SSLContext sslContext, ActorRef listener) {
      this.sslContext = sslContext;
      this.listener = listener;

      // bind to a socket, registering ourselves as incoming connection handler
      Tcp.get(getContext().system()).getManager().tell(
          TcpMessage.bind(getSelf(), new InetSocketAddress("localhost", 0), 100),
          getSelf());
    }
    
    class Context extends AbstractPipelineContext implements HasLogging {
      @Override
      public LoggingAdapter getLogger() {
        return log;
      }
    }
    
    Init<HasLogging, Command, Event> init = null;

    @Override
    public void onReceive(Object msg) {
      if (msg instanceof CommandFailed) {
        getContext().stop(getSelf());
        
      } else if (msg instanceof Bound) {
        listener.tell(msg, getSelf());
        
      } else if (msg instanceof Connected) {
        // create a javax.net.ssl.SSLEngine for our peer in server mode
        final InetSocketAddress remote = ((Connected) msg).remoteAddress();
        final SSLEngine engine = sslContext.createSSLEngine(
            remote.getHostName(), remote.getPort());
        engine.setUseClientMode(false);
        final SslTlsSupport ssl = new SslTlsSupport(engine);

        // set up the context for communicating with TcpPipelineHandler
        init = new Init<HasLogging, Command, Event>(ssl) {
          @Override
          public HasLogging makeContext(ActorContext ctx) {
            return new Context();
          }
        };
        // create handler for pipeline, setting ourselves as payload recipient
        final ActorRef handler = getContext().actorOf(
            TcpPipelineHandler.create(init, getSender(), getSelf()));
        
        // register the SSL handler with the connection
        getSender().tell(TcpMessage.register(handler), getSelf());
      
      } else if (msg instanceof Init.Event) {
        // unwrap TcpPipelineHandler’s event to get a Tcp.Event
        final Event recv = init.event(msg);
        if (recv instanceof Received) {
          // inform someone of the received message
          listener.tell(((Received) recv).data().utf8String(), getSelf());
          // and reply (sender is the SSL handler created above)
          getSender().tell(init.command(
              TcpMessage.write(ByteString.fromString("world"))), getSelf());
        }
      }
    }
  }
  //#server

  private static ActorSystem system;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("IODocTest", AkkaSpec.testConf());
  }
  
  @AfterClass
  public static void teardown() {
    system.shutdown();
  }
  
  @Test
  public void demonstrateSslClient() {
    new JavaTestKit(system) {
      {
        final SSLContext ctx = SslTlsSupportSpec.createSslContext("/keystore", "/truststore", "changeme");
        
        final ActorRef server = system.actorOf(Props.create(SslServer.class, ctx, getRef()));
        final Bound bound = expectMsgClass(Bound.class);
        assert getLastSender() == server;
        
        final ActorRef client = system.actorOf(Props.create(SslClient.class, bound.localAddress(), ctx, getRef()));
        expectMsgEquals("hello");
        assert getLastSender() == server;
        expectMsgEquals("world");
        assert getLastSender() == client;
      }
    };
  }
 
}
