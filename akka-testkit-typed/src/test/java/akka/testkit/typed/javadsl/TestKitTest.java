package akka.testkit.typed.javadsl;

import akka.Done;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
public class TestKitTest extends JUnitSuite {

  @ClassRule
  public static TestKitJunitResource testKit = new TestKitJunitResource(TestKitTest.class);

  @Test
  public void systemNameShouldComeFromTest() {
    assertEquals(testKit.system().name(), "TestKitTest");
  }

  @Test
  public void testKitShouldSpawnActor() throws Exception {
    final CompletableFuture<Done> started = new CompletableFuture<>();
    testKit.spawn(Behaviors.deferred((ctx) -> {
      started.complete(Done.getInstance());
      return Behaviors.same();
    }));
    started.get(3, TimeUnit.SECONDS);
  }
}
