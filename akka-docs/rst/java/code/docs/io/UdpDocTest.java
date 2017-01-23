/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io;

//#imports
import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;
import akka.io.Udp;
import akka.io.UdpConnected;
import akka.io.UdpConnectedMessage;
import akka.io.UdpMessage;
import akka.japi.Procedure;
import akka.util.ByteString;

import java.net.InetSocketAddress;
//#imports

public class UdpDocTest {

  //#sender
  public static class SimpleSender extends UntypedActor {
    final InetSocketAddress remote;

    public SimpleSender(InetSocketAddress remote) {
      this.remote = remote;
      
      // request creation of a SimpleSender
      final ActorRef mgr = Udp.get(getContext().system()).getManager();
      mgr.tell(UdpMessage.simpleSender(), self());
    }
    
    @Override
    public void onReceive(Object msg) {
      if (msg instanceof Udp.SimpleSenderReady) {
        getContext().become(ready(sender()));
        //#sender
        sender().tell(UdpMessage.send(ByteString.fromString("hello"), remote), self());
        //#sender
      } else unhandled(msg);
    }
    
    private Procedure<Object> ready(final ActorRef send) {
      return new Procedure<Object>() {
        @Override
        public void apply(Object msg) throws Exception {
          if (msg instanceof String) {
            final String str = (String) msg;
            send.tell(UdpMessage.send(ByteString.fromString(str), remote), self());
            //#sender
            if (str.equals("world")) {
              send.tell(PoisonPill.getInstance(), self());
            }
            //#sender

          } else unhandled(msg);
        }
      };
    }
  }
  //#sender
  
  //#listener
  public static class Listener extends UntypedActor {
    final ActorRef nextActor;

    public Listener(ActorRef nextActor) {
      this.nextActor = nextActor;
      
      // request creation of a bound listen socket
      final ActorRef mgr = Udp.get(getContext().system()).getManager();
      mgr.tell(
          UdpMessage.bind(self(), new InetSocketAddress("localhost", 0)),
          self());
    }

    @Override
    public void onReceive(Object msg) {
      if (msg instanceof Udp.Bound) {
        final Udp.Bound b = (Udp.Bound) msg;
        //#listener
        nextActor.tell(b.localAddress(), sender());
        //#listener
        getContext().become(ready(sender()));
      } else unhandled(msg);
    }
    
    private Procedure<Object> ready(final ActorRef socket) {
      return new Procedure<Object>() {
        @Override
        public void apply(Object msg) throws Exception {
          if (msg instanceof Udp.Received) {
            final Udp.Received r = (Udp.Received) msg;
            // echo server example: send back the data
            socket.tell(UdpMessage.send(r.data(), r.sender()), self());
            // or do some processing and forward it on
            final Object processed = // parse data etc., e.g. using PipelineStage
                //#listener
                r.data().utf8String();
            //#listener
            nextActor.tell(processed, self());
            
          } else if (msg.equals(UdpMessage.unbind())) {
            socket.tell(msg, self());
          
          } else if (msg instanceof Udp.Unbound) {
            getContext().stop(self());
            
          } else unhandled(msg);
        }
      };
    }
  }
  //#listener
  
  //#connected
  public static class Connected extends UntypedActor  {
    final InetSocketAddress remote;

    public Connected(InetSocketAddress remote) {
      this.remote = remote;
      
      // create a restricted a.k.a. “connected” socket
      final ActorRef mgr = UdpConnected.get(getContext().system()).getManager();
      mgr.tell(UdpConnectedMessage.connect(self(), remote), self());
    }
    
    @Override
    public void onReceive(Object msg) {
      if (msg instanceof UdpConnected.Connected) {
        getContext().become(ready(sender()));
        //#connected
        sender()
            .tell(UdpConnectedMessage.send(ByteString.fromString("hello")),
                self());
        //#connected
      } else unhandled(msg);
    }
    
    private Procedure<Object> ready(final ActorRef connection) {
      return new Procedure<Object>() {
        @Override
        public void apply(Object msg) throws Exception {
          if (msg instanceof UdpConnected.Received) {
            final UdpConnected.Received r = (UdpConnected.Received) msg;
            // process data, send it on, etc.
            // #connected
            if (r.data().utf8String().equals("hello")) {
              connection.tell(
                  UdpConnectedMessage.send(ByteString.fromString("world")),
                  self());
            }
            // #connected
            
          } else if (msg instanceof String) {
            final String str = (String) msg;
            connection
                .tell(UdpConnectedMessage.send(ByteString.fromString(str)),
                    self());
          
          } else if (msg.equals(UdpConnectedMessage.disconnect())) {
            connection.tell(msg, self());
          
          } else if (msg instanceof UdpConnected.Disconnected) {
            getContext().stop(self());
          
          } else unhandled(msg);
        }
      };
    }
  }
  //#connected

}
