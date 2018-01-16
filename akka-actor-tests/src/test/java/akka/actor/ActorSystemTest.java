 /**
  * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
  */ 
package akka.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;

public class ActorSystemTest extends JUnitSuite {

  @Rule
  public final AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("ActorSystemTest");

  private ActorSystem system = null;

  @Before
  public void beforeEach() {
    system = actorSystemResource.getSystem();
  }

  @Test
  public void testGetWhenTerminated() throws Exception {
    system.terminate();
    final CompletionStage<Terminated> cs = system.getWhenTerminated();
    cs.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void testGetWhenTerminatedWithoutTermination() throws Exception {
    final CompletionStage<Terminated> cs = system.getWhenTerminated();
    try {
      cs.toCompletableFuture().get(2, SECONDS);
      Assert.fail("System wasn't terminated, but getWhenTerminated() unexpectedly completed");
    } catch (TimeoutException e) {
      // we expect this to be thrown, because system wasn't terminated
    } finally {
      system.terminate();
    }
  }
}
