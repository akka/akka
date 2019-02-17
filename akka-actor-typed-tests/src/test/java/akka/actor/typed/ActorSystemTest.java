/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

/** Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com> */
package akka.actor.typed;

import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import java.util.concurrent.CompletionStage;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertFalse;

public class ActorSystemTest extends JUnitSuite {

  @Test
  public void testGetWhenTerminated() throws Exception {
    final ActorSystem system = ActorSystem.create(Behavior.empty(), "GetWhenTerminatedSystem");
    system.terminate();
    final CompletionStage<Terminated> cs = system.getWhenTerminated();
    cs.toCompletableFuture().get(2, SECONDS);
  }

  @Test
  public void testGetWhenTerminatedWithoutTermination() {
    final ActorSystem system =
        ActorSystem.create(Behavior.empty(), "GetWhenTerminatedWithoutTermination");
    assertFalse(system.getWhenTerminated().toCompletableFuture().isDone());
  }
}
