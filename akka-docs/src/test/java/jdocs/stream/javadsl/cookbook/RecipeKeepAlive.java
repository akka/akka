/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.testkit.javadsl.TestKit;
import akka.util.ByteString;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;

public class RecipeKeepAlive extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeKeepAlive");
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  class Tick {}
  public final Tick TICK = new Tick();

  @Test
  public void workForVersion1() throws Exception {
    new TestKit(system) {
      {
        final ByteString keepAliveMessage = ByteString.fromArray(new byte[]{11});

        //@formatter:off
        //#inject-keepalive
        Flow<ByteString, ByteString, NotUsed> keepAliveInject =
          Flow.of(ByteString.class).keepAlive(
                  Duration.ofSeconds(1),
                  () -> keepAliveMessage);
        //#inject-keepalive
        //@formatter:on

        // Enough to compile, tested elsewhere as a built-in stage
      }
    };
  }

}
