/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.Done;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

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
}
