/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;

import java.time.Duration;
import java.util.List;

public class TestProbeTest {

  public static void compileOnlyApiTest() {
    ActorSystem<Object> system = null;
    TestProbe<String> probe = TestProbe.create(system);
    probe.ref();
    probe.awaitAssert(() -> {
      // ... something ...
      return null;
    });
    probe.awaitAssert(Duration.ofSeconds(3), () -> {
      // ... something ...
      return null;
    });
    String awaitAssertResult =
      probe.awaitAssert(Duration.ofSeconds(3), Duration.ofMillis(100), () -> {
        // ... something ...
        return "some result";
      });
    String messageResult = probe.expectMessage("message");
    String expectClassResult = probe.expectMessageClass(String.class);
    probe.expectNoMessage();

    ActorRef<String> ref = null;
    probe.expectTerminated(ref, Duration.ofSeconds(1));

    Duration remaining = probe.getRemaining();
    probe.fishForMessage(Duration.ofSeconds(3), "hint", (msg) -> {
      if (msg.equals("one")) return FishingOutcomes.continueAndIgnore();
      else if (msg.equals("two")) return FishingOutcomes.complete();
      else return FishingOutcomes.fail("error");
    });

    String withinResult = probe.within(Duration.ofSeconds(3), () -> {
      // ... something ...
      return "result";
    });

    List<String> messages1 = probe.receiveMessages(3);
    List<String> messages2 = probe.receiveMessages(3, Duration.ofSeconds(5));

  }
}
