/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.Done;
import akka.actor.typed.javadsl.Behaviors;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static akka.Done.done;
import static org.junit.Assert.assertEquals;

public class ActorTestKitTest extends JUnitSuite {

  @ClassRule public static TestKitJunitResource testKit = new TestKitJunitResource();

  @Test
  public void systemNameShouldComeFromTestClassViaJunitResource() {
    assertEquals("ActorTestKitTest", testKit.system().name());
  }

  @Test
  public void systemNameShouldComeFromTestClass() {
    final ActorTestKit testKit2 = ActorTestKit.create();
    try {
      assertEquals("ActorTestKitTest", testKit2.system().name());
    } finally {
      testKit2.shutdownTestKit();
    }
  }

  @Test
  public void systemNameShouldComeFromGivenClassName() {
    final ActorTestKit testKit2 = ActorTestKit.create(HashMap.class.getName());
    try {
      // removing package name and such
      assertEquals("HashMap", testKit2.system().name());
    } finally {
      testKit2.shutdownTestKit();
    }
  }

  @Test
  public void testKitShouldSpawnActor() throws Exception {
    final CompletableFuture<Done> started = new CompletableFuture<>();
    testKit.spawn(
        Behaviors.setup(
            (context) -> {
              started.complete(done());
              return Behaviors.same();
            }));
    started.get(3, TimeUnit.SECONDS);
  }
}
