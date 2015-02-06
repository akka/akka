/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.Context;
import akka.stream.stage.Directive;
import akka.stream.stage.PushPullStage;
import akka.stream.stage.PushStage;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import docs.stream.SilenceSystemOut;
import junit.framework.Assert;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.Tuple2;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RecipeByteStrings extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeByteStrings");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  final Source<ByteString> rawBytes = Source.from(Arrays.asList(
    ByteString.fromArray(new byte[]{1, 2}),
    ByteString.fromArray(new byte[]{3}),
    ByteString.fromArray(new byte[]{4, 5, 6}),
    ByteString.fromArray(new byte[]{7, 8, 9})));


  @Test
  public void chunker() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      final int CHUNK_LIMIT = 2;

      //#bytestring-chunker
      class Chunker extends PushPullStage<ByteString, ByteString> {
        private final int chunkSize;
        private ByteString buffer = ByteString.empty();

        public Chunker(int chunkSize) {
          this.chunkSize = chunkSize;
        }

        @Override
        public Directive onPush(ByteString elem, Context<ByteString> ctx) {
          buffer = buffer.concat(elem);
          return emitChunkOrPull(ctx);
        }

        @Override
        public Directive onPull(Context<ByteString> ctx) {
          return emitChunkOrPull(ctx);
        }

        public Directive emitChunkOrPull(Context<ByteString> ctx) {
          if (buffer.isEmpty()) {
            return ctx.pull();
          } else {
            Tuple2<ByteString, ByteString> split = buffer.splitAt(chunkSize);
            ByteString emit = split._1();
            buffer = split._2();
            return ctx.push(emit);
          }
        }

      }

      //#bytestring-chunker


      {

        //#bytestring-chunker
        Source<ByteString> chunksStream = rawBytes.transform(() -> new Chunker(CHUNK_LIMIT));
        //#bytestring-chunker

        Future<List<ByteString>> chunksFuture = chunksStream.grouped(10).runWith(Sink.head(), mat);

        List<ByteString> chunks = Await.result(chunksFuture, FiniteDuration.create(3, TimeUnit.SECONDS));

        for (ByteString chunk : chunks) {
          assertTrue(chunk.length() <= 2);
        }

        ByteString sum = ByteString.empty();
        for (ByteString chunk : chunks) {
          sum = sum.concat(chunk);
        }
        assertEquals(sum, ByteString.fromArray(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
      }

    };
  }

  @Test
  public void limiterShouldWork() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      final int SIZE_LIMIT = 9;

      //#bytes-limiter
      class ByteLimiter extends PushStage<ByteString, ByteString> {
        final long maximumBytes;
        private int count = 0;

        public ByteLimiter(long maximumBytes) {
          this.maximumBytes = maximumBytes;
        }

        @Override
        public Directive onPush(ByteString chunk, Context<ByteString> ctx) {
          count += chunk.size();
          if (count > maximumBytes) {
            return ctx.fail(new IllegalStateException("Too much bytes"));
          } else {
            return ctx.push(chunk);
          }
        }
      }

      //#bytes-limiter


      {
        //#bytes-limiter
        Flow<ByteString, ByteString> limiter =
          Flow.of(ByteString.class).transform(() -> new ByteLimiter(SIZE_LIMIT));
        //#bytes-limiter

        final Source<ByteString> bytes1 = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[]{1, 2}),
          ByteString.fromArray(new byte[]{3}),
          ByteString.fromArray(new byte[]{4, 5, 6}),
          ByteString.fromArray(new byte[]{7, 8, 9})));

        final Source<ByteString> bytes2 = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[]{1, 2}),
          ByteString.fromArray(new byte[]{3}),
          ByteString.fromArray(new byte[]{4, 5, 6}),
          ByteString.fromArray(new byte[]{7, 8, 9, 10})));

        FiniteDuration threeSeconds = FiniteDuration.create(3, TimeUnit.SECONDS);

        List<ByteString> got = Await.result(bytes1.via(limiter).grouped(10).runWith(Sink.head(), mat), threeSeconds);
        ByteString acc = ByteString.empty();
        for (ByteString b : got) {
          acc = acc.concat(b);
        }
        assertEquals(acc, ByteString.fromArray(new byte[]{1,2,3,4,5,6,7,8,9}));


        boolean thrown = false;
        try {
          Await.result(bytes2.via(limiter).grouped(10).runWith(Sink.head(), mat), threeSeconds);
        } catch(IllegalStateException ex) {
          thrown = true;
        }

        assertTrue("Expected IllegalStateException to be thrown", thrown);
      }
    };
  }

  @Test
  public void compacting() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<ByteString> rawBytes = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[]{1, 2}),
          ByteString.fromArray(new byte[]{3}),
          ByteString.fromArray(new byte[]{4, 5, 6}),
          ByteString.fromArray(new byte[]{7, 8, 9})));

        //#compacting-bytestrings
        Source<ByteString> compacted = rawBytes.map(bs -> bs.compact());
        //#compacting-bytestrings

        FiniteDuration timeout = FiniteDuration.create(3, TimeUnit.SECONDS);
        List<ByteString> got = Await.result(compacted.grouped(10).runWith(Sink.head(), mat), timeout);

        for (ByteString byteString : got) {
          assertTrue(byteString.isCompact());
        }
      }
    };
  }

}

