/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.Context;
import akka.stream.stage.Directive;
import akka.stream.stage.PushPullStage;
import akka.stream.stage.TerminationDirective;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.math.Numeric;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class RecipeDigest extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeDigest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      //#calculating-digest
      public PushPullStage<ByteString, ByteString> digestCalculator(String algorithm) throws NoSuchAlgorithmException {
        return new PushPullStage<ByteString, ByteString>() {
          final MessageDigest digest = MessageDigest.getInstance(algorithm);

          @Override
          public Directive onPush(ByteString chunk, Context<ByteString> ctx) {
            digest.update(chunk.toArray());
            return ctx.pull();
          }

          @Override
          public Directive onPull(Context<ByteString> ctx) {
            if (ctx.isFinishing()) {
              return ctx.pushAndFinish(ByteString.fromArray(digest.digest()));
            } else {
              return ctx.pull();
            }
          }

          @Override
          public TerminationDirective onUpstreamFinish(Context<ByteString> ctx) {
            // If the stream is finished, we need to emit the last element in the onPull block.
            // It is not allowed to directly emit elements from a termination block
            // (onUpstreamFinish or onUpstreamFailure)
            return ctx.absorbTermination();
          }
        };
      }

      {
        Source<ByteString> data = Source.from(Arrays.asList(
          ByteString.fromString("abcdbcdecdef"),
          ByteString.fromString("defgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        ));


        //#calculating-digest

        final Source<ByteString> digest = data.transform(() -> digestCalculator("SHA-256"));
        //#calculating-digest

        ByteString got = Await.result(digest.runWith(Sink.head(), mat), Duration.create(3, TimeUnit.SECONDS));
        assertEquals(MyByteString.fromInts( // TODO: Use Java API once akka-actor released with fromInts (2.3.10)
          0x24, 0x8d, 0x6a, 0x61,
          0xd2, 0x06, 0x38, 0xb8,
          0xe5, 0xc0, 0x26, 0x93,
          0x0c, 0x3e, 0x60, 0x39,
          0xa3, 0x3c, 0xe4, 0x59,
          0x64, 0xff, 0x21, 0x67,
          0xf6, 0xec, 0xed, 0xd4,
          0x19, 0xdb, 0x06, 0xc1
        ), got);
      }
    };
  }
}

// TODO remove this once ByteString#fromInts in akka-actor is released
class MyByteString {
  final static Numeric.ByteIsIntegral Byte = Numeric.ByteIsIntegral$.MODULE$;

  public static akka.util.ByteString fromInts(int... bytes) {
    byte[] converted = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      converted[i] = Byte.fromInt(bytes[i]);
    }
    return akka.util.ByteString.fromArray(converted);
  }
}