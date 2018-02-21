/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class TestProbeTest {

  public static void compileOnlyApiTest() {
    ActorSystem<Object> system = null;
    TestProbe<String> probe = TestProbe.create(system);
    probe.ref();
    probe.awaitAssert(() -> {
      // ... something ...
      return null;
    });
    probe.awaitAssert(FiniteDuration.apply(3, TimeUnit.SECONDS), () -> {
      // ... something ...
      return null;
    });
    String awaitAssertResult =
      probe.awaitAssert(FiniteDuration.apply(3, TimeUnit.SECONDS), FiniteDuration.apply(100, TimeUnit.MILLISECONDS), () -> {
        // ... something ...
        return "some result";
      });
    String messageResult = probe.expectMessage("message");
    String expectClassResult = probe.expectMessageClass(String.class);
    probe.expectNoMessage();

    ActorRef<String> ref = null;
    probe.expectTerminated(ref, FiniteDuration.apply(1, TimeUnit.SECONDS));

    FiniteDuration remaining = probe.remaining();
    probe.fishForMessage(FiniteDuration.apply(3, TimeUnit.SECONDS), "hint", (msg) -> {
      if (msg.equals("one")) return FishingOutcomes.continueAndIgnore();
      else if (msg.equals("two")) return FishingOutcomes.complete();
      else return FishingOutcomes.fail("error");
    });

    String withinResult = probe.within(FiniteDuration.apply(3, TimeUnit.SECONDS), () -> {
      // ... something ...
      return "result";
    });

  }
}
