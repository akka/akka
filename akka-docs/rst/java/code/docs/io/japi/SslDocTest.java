/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.AbstractPipelineContext;
import akka.io.BackpressureBuffer;
import akka.io.DelimiterFraming;
import akka.io.HasLogging;
import akka.io.PipelineStage;
import static akka.io.PipelineStage.sequence;
import akka.io.SslTlsSupport;
import akka.io.StringByteStringAdapter;
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
import akka.io.TcpPipelineHandler.WithinActorContext;
import akka.io.TcpReadWriteAdapter;
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
    
    // this will hold the pipeline handler’s context
    Init<WithinActorContext, String, String> init = null;

    @Override
    public void onReceive(Object msg) {
      if (msg instanceof CommandFailed) {
        getContext().stop(getSelf());
        
      } else if (msg instanceof Connected) {
        // create a javax.net.ssl.SSLEngine for our peer in client mode
        final SSLEngine engine = sslContext.createSSLEngine(
            remote.getHostName(), remote.getPort());
        engine.setUseClientMode(true);

        // build pipeline and set up context for communicating with TcpPipelineHandler
        init = TcpPipelineHandler.withLogger(log, sequence(sequence(sequence(sequence(
            new StringByteStringAdapter("utf-8"),
            new DelimiterFraming(1024, ByteString.fromString("\n"), true)),
            new TcpReadWriteAdapter()),
            new SslTlsSupport(engine)),
            new BackpressureBuffer(1000, 10000, 1000000)));

        // create handler for pipeline, setting ourselves as payload recipient
        final ActorRef handler = getContext().actorOf(
            TcpPipelineHandler.create(init, getSender(), getSelf()));
        
        // register the SSL handler with the connection
        getSender().tell(TcpMessage.register(handler), getSelf());
        
        // and send a message across the SSL channel
        handler.tell(init.command("hello\n"), getSelf());
      
      } else if (msg instanceof Init.Event) {
        // unwrap TcpPipelineHandler’s event into a Tcp.Event
        final String recv = init.event(msg);
        // and inform someone of the received payload
        listener.tell(recv, getSelf());
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
    
    // this will hold the pipeline handler’s context
    Init<WithinActorContext, String, String> init = null;

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
        
        // build pipeline and set up context for communicating with TcpPipelineHandler
        init = TcpPipelineHandler.withLogger(log, sequence(sequence(sequence(sequence(
            new StringByteStringAdapter("utf-8"),
            new DelimiterFraming(1024, ByteString.fromString("\n"), true)),
            new TcpReadWriteAdapter()),
            new SslTlsSupport(engine)),
            new BackpressureBuffer(1000, 10000, 1000000)));
        
        // create handler for pipeline, setting ourselves as payload recipient
        final ActorRef handler = getContext().actorOf(
            TcpPipelineHandler.create(init, getSender(), getSelf()));
        
        // register the SSL handler with the connection
        getSender().tell(TcpMessage.register(handler), getSelf());
      
      } else if (msg instanceof Init.Event) {
        // unwrap TcpPipelineHandler’s event to get a Tcp.Event
        final String recv = init.event(msg);
        // inform someone of the received message
        listener.tell(recv, getSelf());
        // and reply (sender is the SSL handler created above)
        getSender().tell(init.command("world\n"), getSelf());
      }
    }
  }
  //#server

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("SslDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();
  
  @Test
  public void demonstrateSslClient() {
    new JavaTestKit(system) {
      {
        final SSLContext ctx = SslTlsSupportSpec.createSslContext("/keystore", "/truststore", "changeme");
        
        final ActorRef server = system.actorOf(Props.create(SslServer.class, ctx, getRef()));
        final Bound bound = expectMsgClass(Bound.class);
        assert getLastSender() == server;
        
        final ActorRef client = system.actorOf(Props.create(SslClient.class, bound.localAddress(), ctx, getRef()));
        expectMsgEquals("hello\n");
        assert getLastSender() == server;
        expectMsgEquals("world\n");
        assert getLastSender() == client;
      }
    };
  }
 
}
