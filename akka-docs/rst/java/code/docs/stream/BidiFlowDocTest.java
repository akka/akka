/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import akka.NotUsed;
import akka.stream.javadsl.GraphDSL;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorSystem;
import akka.japi.pf.PFBuilder;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.stage.*;
import akka.testkit.JavaTestKit;
import akka.util.ByteIterator;
import akka.util.ByteString;
import akka.util.ByteStringBuilder;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import static org.junit.Assert.assertArrayEquals;

public class BidiFlowDocTest {

  private static ActorSystem system;

  @BeforeClass
  public static void setup() {
      system = ActorSystem.create("FlowDocTest");
  }

  @AfterClass
  public static void tearDown() {
      JavaTestKit.shutdownActorSystem(system);
      system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  //#codec
  static interface Message {}
  static class Ping implements Message {
    final int id;
    public Ping(int id) { this.id = id; }
    @Override
    public boolean equals(Object o) {
      if (o instanceof Ping) {
        return ((Ping) o).id == id;
      } else return false;
    }
    @Override
    public int hashCode() {
      return id;
    }
  }
  static class Pong implements Message {
    final int id;
    public Pong(int id) { this.id = id; }
    @Override
    public boolean equals(Object o) {
      if (o instanceof Pong) {
        return ((Pong) o).id == id;
      } else return false;
    }
    @Override
    public int hashCode() {
      return id;
    }
  }
  
  //#codec-impl
  public static ByteString toBytes(Message msg) {
    //#implementation-details-elided
    if (msg instanceof Ping) {
      final int id = ((Ping) msg).id;
      return new ByteStringBuilder().putByte((byte) 1)
          .putInt(id, ByteOrder.LITTLE_ENDIAN).result();
    } else {
      final int id = ((Pong) msg).id;
      return new ByteStringBuilder().putByte((byte) 2)
          .putInt(id, ByteOrder.LITTLE_ENDIAN).result();
    }
    //#implementation-details-elided
  }
  
  public static Message fromBytes(ByteString bytes) {
    //#implementation-details-elided
    final ByteIterator it = bytes.iterator();
    switch(it.getByte()) {
    case 1:
      return new Ping(it.getInt(ByteOrder.LITTLE_ENDIAN));
    case 2:
      return new Pong(it.getInt(ByteOrder.LITTLE_ENDIAN));
    default:
      throw new RuntimeException("message format error");
    }
    //#implementation-details-elided
  }
  //#codec-impl
  
  //#codec
  @SuppressWarnings("unused")
  //#codec
  public final BidiFlow<Message, ByteString, ByteString, Message, NotUsed> codecVerbose =
      BidiFlow.fromGraph(GraphDSL.create(b -> {
        final FlowShape<Message, ByteString> top =
                b.add(Flow.of(Message.class).map(BidiFlowDocTest::toBytes));
        final FlowShape<ByteString, Message> bottom =
                b.add(Flow.of(ByteString.class).map(BidiFlowDocTest::fromBytes));
        return BidiShape.fromFlows(top, bottom);
      }));

  public final BidiFlow<Message, ByteString, ByteString, Message, NotUsed> codec =
      BidiFlow.fromFunctions(BidiFlowDocTest::toBytes, BidiFlowDocTest::fromBytes);
  //#codec
  
  //#framing
  public static ByteString addLengthHeader(ByteString bytes) {
    final int len = bytes.size();
    return new ByteStringBuilder()
      .putInt(len, ByteOrder.LITTLE_ENDIAN)
      .append(bytes)
      .result();
  }
  
  public static class FrameParser extends PushPullStage<ByteString, ByteString> {
    // this holds the received but not yet parsed bytes
    private ByteString stash = ByteString.empty();
    // this holds the current message length or -1 if at a boundary
    private int needed = -1;
    
    @Override
    public SyncDirective onPull(Context<ByteString> ctx) {
      return run(ctx);
    }

    @Override
    public SyncDirective onPush(ByteString bytes, Context<ByteString> ctx) {
      stash = stash.concat(bytes);
      return run(ctx);
    }
    
    @Override
    public TerminationDirective onUpstreamFinish(Context<ByteString> ctx) {
      if (stash.isEmpty()) return ctx.finish();
      else return ctx.absorbTermination(); // we still have bytes to emit
    }
    
    private SyncDirective run(Context<ByteString> ctx) {
      if (needed == -1) {
        // are we at a boundary? then figure out next length
        if (stash.size() < 4) return pullOrFinish(ctx);
        else {
          needed = stash.iterator().getInt(ByteOrder.LITTLE_ENDIAN);
          stash = stash.drop(4);
          return run(ctx); // cycle back to possibly already emit the next chunk
        }
      } else if (stash.size() < needed) {
        // we are in the middle of a message, need more bytes
        return pullOrFinish(ctx);
      } else {
        // we have enough to emit at least one message, so do it
        final ByteString emit = stash.take(needed);
        stash = stash.drop(needed);
        needed = -1;
        return ctx.push(emit);
      }
    }
    
    /*
     * After having called absorbTermination() we cannot pull any more, so if we need
     * more data we will just have to give up.
     */
    private SyncDirective pullOrFinish(Context<ByteString> ctx) {
      if (ctx.isFinishing()) return ctx.finish();
      else return ctx.pull();
    }
  }
  
  public final BidiFlow<ByteString, ByteString, ByteString, ByteString, NotUsed> framing =
      BidiFlow.fromGraph(GraphDSL.create(b -> {
        final FlowShape<ByteString, ByteString> top =
                b.add(Flow.of(ByteString.class).map(BidiFlowDocTest::addLengthHeader));
        final FlowShape<ByteString, ByteString> bottom =
                b.add(Flow.of(ByteString.class).transform(() -> new FrameParser()));
        return BidiShape.fromFlows(top, bottom);
      }));
  //#framing
  
  @Test
  public void mustCompose() throws Exception {
    //#compose
    /* construct protocol stack
     *         +------------------------------------+
     *         | stack                              |
     *         |                                    |
     *         |  +-------+            +---------+  |
     *    ~>   O~~o       |     ~>     |         o~~O    ~>
     * Message |  | codec | ByteString | framing |  | ByteString
     *    <~   O~~o       |     <~     |         o~~O    <~
     *         |  +-------+            +---------+  |
     *         +------------------------------------+
     */
    final BidiFlow<Message, ByteString, ByteString, Message, NotUsed> stack =
        codec.atop(framing);

    // test it by plugging it into its own inverse and closing the right end
    final Flow<Message, Message, NotUsed> pingpong =
        Flow.of(Message.class).collect(new PFBuilder<Message, Message>()
            .match(Ping.class, p -> new Pong(p.id))
            .build()
            );
    final Flow<Message, Message, NotUsed> flow =
        stack.atop(stack.reversed()).join(pingpong);
    final CompletionStage<List<Message>> result = Source
        .from(Arrays.asList(0, 1, 2))
        .<Message> map(id -> new Ping(id))
        .via(flow)
        .grouped(10)
        .runWith(Sink.<List<Message>> head(), mat);
    assertArrayEquals(
        new Message[] { new Pong(0), new Pong(1), new Pong(2) },
        result.toCompletableFuture().get(1, TimeUnit.SECONDS).toArray(new Message[0]));
    //#compose
  }
}
