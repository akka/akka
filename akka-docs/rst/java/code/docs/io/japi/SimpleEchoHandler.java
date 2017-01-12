/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io.japi;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.Tcp.ConnectionClosed;
import akka.io.Tcp.Event;
import akka.io.Tcp.Received;
import akka.io.TcpMessage;
import akka.japi.Procedure;
import akka.util.ByteString;

//#simple-echo-handler
public class SimpleEchoHandler extends UntypedActor {
  
  final LoggingAdapter log = Logging
      .getLogger(getContext().system(), getSelf());

  final ActorRef connection;
  final InetSocketAddress remote;

  public static final long maxStored = 100000000;
  public static final long highWatermark = maxStored * 5 / 10;
  public static final long lowWatermark = maxStored * 2 / 10;

  public SimpleEchoHandler(ActorRef connection, InetSocketAddress remote) {
    this.connection = connection;
    this.remote = remote;

    // sign death pact: this actor stops when the connection is closed
    getContext().watch(connection);
  }

  @Override
  public void onReceive(Object msg) throws Exception {
    if (msg instanceof Received) {
      final ByteString data = ((Received) msg).data();
      buffer(data);
      connection.tell(TcpMessage.write(data, ACK), getSelf());
      // now switch behavior to “waiting for acknowledgement”
      getContext().become(buffering, false);

    } else if (msg instanceof ConnectionClosed) {
      getContext().stop(getSelf());
    }
  }

  private final Procedure<Object> buffering = new Procedure<Object>() {
    @Override
    public void apply(Object msg) throws Exception {
      if (msg instanceof Received) {
        buffer(((Received) msg).data());
        
      } else if (msg == ACK) {
        acknowledge();

      } else if (msg instanceof ConnectionClosed) {
        if (((ConnectionClosed) msg).isPeerClosed()) {
          closing = true;
        } else {
          // could also be ErrorClosed, in which case we just give up
          getContext().stop(getSelf());
        }
      }
    }
  };

  //#storage-omitted
  public void postStop() {
    log.info("transferred {} bytes from/to [{}]", transferred, remote);
  }

  private long transferred;
  private long stored = 0;
  private Queue<ByteString> storage = new LinkedList<ByteString>();

  private boolean suspended = false;
  private boolean closing = false;
  
  private final Event ACK = new Event() {};

  //#simple-helpers
  protected void buffer(ByteString data) {
    storage.add(data);
    stored += data.size();

    if (stored > maxStored) {
      log.warning("drop connection to [{}] (buffer overrun)", remote);
      getContext().stop(getSelf());

    } else if (stored > highWatermark) {
      log.debug("suspending reading");
      connection.tell(TcpMessage.suspendReading(), getSelf());
      suspended = true;
    }
  }

  protected void acknowledge() {
    final ByteString acked = storage.remove();
    stored -= acked.size();
    transferred += acked.size();

    if (suspended && stored < lowWatermark) {
      log.debug("resuming reading");
      connection.tell(TcpMessage.resumeReading(), getSelf());
      suspended = false;
    }
    
    if (storage.isEmpty()) {
      if (closing) {
        getContext().stop(getSelf());
      } else {
        getContext().unbecome();
      }
    } else {
      connection.tell(TcpMessage.write(storage.peek(), ACK), getSelf());
    }
  }
  //#simple-helpers
  //#storage-omitted
}
//#simple-echo-handler
