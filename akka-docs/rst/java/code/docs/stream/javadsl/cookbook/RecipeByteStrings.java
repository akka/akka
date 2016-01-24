/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.Context;
import akka.stream.stage.PushPullStage;
import akka.stream.stage.PushStage;
import akka.stream.stage.SyncDirective;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.Tuple2;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

  final Materializer mat = ActorMaterializer.create(system);

  final Source<ByteString, NotUsed> rawBytes = Source.from(Arrays.asList(
    ByteString.fromArray(new byte[] { 1, 2 }),
    ByteString.fromArray(new byte[] { 3 }),
    ByteString.fromArray(new byte[] { 4, 5, 6 }),
    ByteString.fromArray(new byte[] { 7, 8, 9 })));

  @Test
  public void chunker() throws Exception {
    new JavaTestKit(system) {
      final int CHUNK_LIMIT = 2;

      //#bytestring-chunker
      class Chunker extends PushPullStage<ByteString, ByteString> {
        private final int chunkSize;
        private ByteString buffer = ByteString.empty();

        public Chunker(int chunkSize) {
          this.chunkSize = chunkSize;
        }

        @Override
        public SyncDirective onPush(ByteString elem, Context<ByteString> ctx) {
          buffer = buffer.concat(elem);
          return emitChunkOrPull(ctx);
        }

        @Override
        public SyncDirective onPull(Context<ByteString> ctx) {
          return emitChunkOrPull(ctx);
        }

        public SyncDirective emitChunkOrPull(Context<ByteString> ctx) {
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
        //#bytestring-chunker2
        Source<ByteString, NotUsed> chunksStream =
          rawBytes.transform(() -> new Chunker(CHUNK_LIMIT));
        //#bytestring-chunker2

        CompletionStage<List<ByteString>> chunksFuture = chunksStream.grouped(10).runWith(Sink.head(), mat);

        List<ByteString> chunks = chunksFuture.toCompletableFuture().get(3, TimeUnit.SECONDS);

        for (ByteString chunk : chunks) {
          assertTrue(chunk.size() <= 2);
        }

        ByteString sum = ByteString.empty();
        for (ByteString chunk : chunks) {
          sum = sum.concat(chunk);
        }
        assertEquals(sum, ByteString.fromArray(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }));
      }

    };
  }

  @Test
  public void limiterShouldWork() throws Exception {
    new JavaTestKit(system) {
      final int SIZE_LIMIT = 9;

      //#bytes-limiter
      class ByteLimiter extends PushStage<ByteString, ByteString> {
        final long maximumBytes;
        private int count = 0;

        public ByteLimiter(long maximumBytes) {
          this.maximumBytes = maximumBytes;
        }

        @Override
        public SyncDirective onPush(ByteString chunk, Context<ByteString> ctx) {
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
        //#bytes-limiter2
        Flow<ByteString, ByteString, NotUsed> limiter =
          Flow.of(ByteString.class).transform(() -> new ByteLimiter(SIZE_LIMIT));
        //#bytes-limiter2

        final Source<ByteString, NotUsed> bytes1 = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[] { 1, 2 }),
          ByteString.fromArray(new byte[] { 3 }),
          ByteString.fromArray(new byte[] { 4, 5, 6 }),
          ByteString.fromArray(new byte[] { 7, 8, 9 })));

        final Source<ByteString, NotUsed> bytes2 = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[] { 1, 2 }),
          ByteString.fromArray(new byte[] { 3 }),
          ByteString.fromArray(new byte[] { 4, 5, 6 }),
          ByteString.fromArray(new byte[] { 7, 8, 9, 10 })));

        List<ByteString> got = bytes1.via(limiter).grouped(10).runWith(Sink.head(), mat).toCompletableFuture().get(3, TimeUnit.SECONDS);
        ByteString acc = ByteString.empty();
        for (ByteString b : got) {
          acc = acc.concat(b);
        }
        assertEquals(acc, ByteString.fromArray(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 }));

        boolean thrown = false;
        try {
          bytes2.via(limiter).grouped(10).runWith(Sink.head(), mat).toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (IllegalStateException ex) {
          thrown = true;
        }

        assertTrue("Expected IllegalStateException to be thrown", thrown);
      }
    };
  }

  @Test
  public void compacting() throws Exception {
    new JavaTestKit(system) {
      {
        final Source<ByteString, NotUsed> rawBytes = Source.from(Arrays.asList(
          ByteString.fromArray(new byte[] { 1, 2 }),
          ByteString.fromArray(new byte[] { 3 }),
          ByteString.fromArray(new byte[] { 4, 5, 6 }),
          ByteString.fromArray(new byte[] { 7, 8, 9 })));

        //#compacting-bytestrings
        Source<ByteString, NotUsed> compacted = rawBytes.map(bs -> bs.compact());
        //#compacting-bytestrings

        List<ByteString> got = compacted.grouped(10).runWith(Sink.head(), mat).toCompletableFuture().get(3, TimeUnit.SECONDS);

        for (ByteString byteString : got) {
          assertTrue(byteString.isCompact());
        }
      }
    };
  }

}
