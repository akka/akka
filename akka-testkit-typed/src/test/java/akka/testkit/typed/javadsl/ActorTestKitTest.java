/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl;

import akka.Done;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
public class ActorTestKitTest extends JUnitSuite {

  @ClassRule
  public static TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void systemNameShouldComeFromTest() {
    assertEquals("ActorTestKitTest", testKit.system().name());
  }

  @Test
  public void testKitShouldSpawnActor() throws Exception {
    final CompletableFuture<Done> started = new CompletableFuture<>();
    testKit.spawn(Behaviors.setup((ctx) -> {
      started.complete(Done.getInstance());
      return Behaviors.same();
    }));
    started.get(3, TimeUnit.SECONDS);
  }

  @Test
  public void testKitShouldRunComputationWithinGivenTime() {

//    Supplier<String> f = () -> {
//      return "str";
//    };

    testKit.within(Duration.create(500, TimeUnit.MILLISECONDS), () -> {
      testKit.setFinished(true);
      return "Success";
    });
  }

  @Test(expected = java.lang.AssertionError.class)
  public void testKitShouldFailComputationWithinGivenTime() {

    testKit.within(Duration.create(500, TimeUnit.MILLISECONDS), () -> {
      try {
        Thread.sleep(600L);
      } catch(InterruptedException e) {
        // Do nothing in the catch block. Software Engineering at its best.
      }
      return "Success";
    });
  }

}
