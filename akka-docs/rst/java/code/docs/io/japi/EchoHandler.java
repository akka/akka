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
import akka.io.Tcp.CommandFailed;
import akka.io.Tcp.ConnectionClosed;
import akka.io.Tcp.Event;
import akka.io.Tcp.Received;
import akka.io.Tcp.Write;
import akka.io.Tcp.WritingResumed;
import akka.io.TcpMessage;
import akka.japi.Procedure;
import akka.util.ByteString;

//#echo-handler
public class EchoHandler extends UntypedActor {

  final LoggingAdapter log = Logging
      .getLogger(getContext().system(), getSelf());

  final ActorRef connection;
  final InetSocketAddress remote;

  public static final long MAX_STORED = 100000000;
  public static final long HIGH_WATERMARK = MAX_STORED * 5 / 10;
  public static final long LOW_WATERMARK = MAX_STORED * 2 / 10;
  
  private static class Ack implements Event {
    public final int ack;
    public Ack(int ack) {
      this.ack = ack;
    }
  }

  public EchoHandler(ActorRef connection, InetSocketAddress remote) {
    this.connection = connection;
    this.remote = remote;

    // sign death pact: this actor stops when the connection is closed
    getContext().watch(connection);

    // start out in optimistic write-through mode
    getContext().become(writing);
  }

  private final Procedure<Object> writing = new Procedure<Object>() {
    @Override
    public void apply(Object msg) throws Exception {
      if (msg instanceof Received) {
        final ByteString data = ((Received) msg).data();
        connection.tell(TcpMessage.write(data, new Ack(currentOffset())), getSelf());
        buffer(data);

      } else if (msg instanceof Integer) {
        acknowledge((Integer) msg);

      } else if (msg instanceof CommandFailed) {
        final Write w = (Write) ((CommandFailed) msg).cmd();
        connection.tell(TcpMessage.resumeWriting(), getSelf());
        getContext().become(buffering((Ack) w.ack()));

      } else if (msg instanceof ConnectionClosed) {
        final ConnectionClosed cl = (ConnectionClosed) msg;
        if (cl.isPeerClosed()) {
          if (storage.isEmpty()) {
            getContext().stop(getSelf());
          } else {
            getContext().become(closing);
          }
        }
      }
    }
  };

  //#buffering
  protected Procedure<Object> buffering(final Ack nack) {
    return new Procedure<Object>() {

      private int toAck = 10;
      private boolean peerClosed = false;

      @Override
      public void apply(Object msg) throws Exception {
        if (msg instanceof Received) {
          buffer(((Received) msg).data());

        } else if (msg instanceof WritingResumed) {
          writeFirst();

        } else if (msg instanceof ConnectionClosed) {
          if (((ConnectionClosed) msg).isPeerClosed())
            peerClosed = true;
          else
            getContext().stop(getSelf());

        } else if (msg instanceof Integer) {
          final int ack = (Integer) msg;
          acknowledge(ack);

          if (ack >= nack.ack) {
            // otherwise it was the ack of the last successful write

            if (storage.isEmpty()) {
              if (peerClosed)
                getContext().stop(getSelf());
              else
                getContext().become(writing);

            } else {
              if (toAck > 0) {
                // stay in ACK-based mode for a short while
                writeFirst();
                --toAck;
              } else {
                // then return to NACK-based again
                writeAll();
                if (peerClosed)
                  getContext().become(closing);
                else
                  getContext().become(writing);
              }
            }
          }
        }
      }
    };
  }
  //#buffering

  //#closing
  protected Procedure<Object> closing = new Procedure<Object>() {
    @Override
    public void apply(Object msg) throws Exception {
      if (msg instanceof CommandFailed) {
        // the command can only have been a Write
        connection.tell(TcpMessage.resumeWriting(), getSelf());
        getContext().become(closeResend, false);
      } else if (msg instanceof Integer) {
        acknowledge((Integer) msg);
        if (storage.isEmpty())
          getContext().stop(getSelf());
      }
    }
  };

  protected Procedure<Object> closeResend = new Procedure<Object>() {
    @Override
    public void apply(Object msg) throws Exception {
      if (msg instanceof WritingResumed) {
        writeAll();
        getContext().unbecome();
      } else if (msg instanceof Integer) {
        acknowledge((Integer) msg);
      }
    }
  };
  //#closing

  //#storage-omitted
  @Override
  public void onReceive(Object msg) throws Exception {
    // this method is not used due to become()
  }

  @Override
  public void postStop() {
    log.info("transferred {} bytes from/to [{}]", transferred, remote);
  }

  private long transferred;
  private int storageOffset = 0;
  private long stored = 0;
  private Queue<ByteString> storage = new LinkedList<ByteString>();

  private boolean suspended = false;

  //#helpers
  protected void buffer(ByteString data) {
    storage.add(data);
    stored += data.size();

    if (stored > MAX_STORED) {
      log.warning("drop connection to [{}] (buffer overrun)", remote);
      getContext().stop(getSelf());

    } else if (stored > HIGH_WATERMARK) {
      log.debug("suspending reading at {}", currentOffset());
      connection.tell(TcpMessage.suspendReading(), getSelf());
      suspended = true;
    }
  }

  protected void acknowledge(int ack) {
    assert ack == storageOffset;
    assert !storage.isEmpty();

    final ByteString acked = storage.remove();
    stored -= acked.size();
    transferred += acked.size();
    storageOffset += 1;

    if (suspended && stored < LOW_WATERMARK) {
      log.debug("resuming reading");
      connection.tell(TcpMessage.resumeReading(), getSelf());
      suspended = false;
    }
  }
  //#helpers

  protected int currentOffset() {
    return storageOffset + storage.size();
  }

  protected void writeAll() {
    int i = 0;
    for (ByteString data : storage) {
      connection.tell(TcpMessage.write(data, new Ack(storageOffset + i++)), getSelf());
    }
  }

  protected void writeFirst() {
    connection.tell(TcpMessage.write(storage.peek(), new Ack(storageOffset)), getSelf());
  }

  //#storage-omitted
}
//#echo-handler
