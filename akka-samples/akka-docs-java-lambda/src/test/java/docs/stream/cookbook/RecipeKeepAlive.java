/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.*;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import scala.runtime.BoxedUnit;

import org.reactivestreams.Subscription;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class RecipeKeepAlive extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeKeepAlive");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  class Tick {}
  public final Tick TICK = new Tick();

  @Test
  public void workForVersion1() throws Exception {
    new JavaTestKit(system) {
      {
        final ByteString keepAliveMessage = ByteString.fromArray(new byte[]{11});

        //@formatter:off
        //#inject-keepalive
        Flow<ByteString, ByteString, BoxedUnit> keepAliveInject =
          Flow.of(ByteString.class).keepAlive(
              scala.concurrent.duration.Duration.create(1, TimeUnit.SECONDS),
              () -> keepAliveMessage);
        //#inject-keepalive
        //@formatter:on

        // Enough to compile, tested elsewhere as a built-in stage
      }
    };
  }

}
