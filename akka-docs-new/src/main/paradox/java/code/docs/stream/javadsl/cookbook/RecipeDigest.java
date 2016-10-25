/**
 *  Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package docs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.*;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.*;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class RecipeDigest extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeDigest");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }


  //#calculating-digest
  class DigestCalculator extends GraphStage<FlowShape<ByteString, ByteString>> {
    private final String algorithm;
    public Inlet<ByteString> in = Inlet.<ByteString>create("DigestCalculator.in");
    public Outlet<ByteString> out = Outlet.<ByteString>create("DigestCalculator.out");
    private FlowShape<ByteString, ByteString> shape = FlowShape.of(in, out);

    public DigestCalculator(String algorithm) {
      this.algorithm = algorithm;
    }

    @Override
    public FlowShape<ByteString, ByteString> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogic(shape) {
        final MessageDigest digest;

        {
          try {
            digest = MessageDigest.getInstance(algorithm);
          } catch(NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
          }

          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() throws Exception {
              pull(in);
            }
          });
          setHandler(in, new AbstractInHandler() {
            @Override
            public void onPush() throws Exception {
              ByteString chunk = grab(in);
              digest.update(chunk.toArray());
              pull(in);
            }

            @Override
            public void onUpstreamFinish() throws Exception {
              // If the stream is finished, we need to emit the digest
              // before completing
              emit(out, ByteString.fromArray(digest.digest()));
              completeStage();
            }
          });
        }


      };
    }

  }
  //#calculating-digest

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {

      {
        Source<ByteString, NotUsed> data = Source.from(Arrays.asList(
          ByteString.fromString("abcdbcdecdef"),
          ByteString.fromString("defgefghfghighijhijkijkljklmklmnlmnomnopnopq")));

        //#calculating-digest2
        final Source<ByteString, NotUsed> digest = data
          .via(new DigestCalculator("SHA-256"));
        //#calculating-digest2

        ByteString got = digest.runWith(Sink.head(), mat).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(ByteString.fromInts(
          0x24, 0x8d, 0x6a, 0x61,
          0xd2, 0x06, 0x38, 0xb8,
          0xe5, 0xc0, 0x26, 0x93,
          0x0c, 0x3e, 0x60, 0x39,
          0xa3, 0x3c, 0xe4, 0x59,
          0x64, 0xff, 0x21, 0x67,
          0xf6, 0xec, 0xed, 0xd4,
          0x19, 0xdb, 0x06, 0xc1), got);
      }
    };
  }
}
