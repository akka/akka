/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.actor.testkit.typed.javadsl;

// #manual-scheduling-simple

import akka.actor.typed.Behavior;
import akka.actor.testkit.typed.javadsl.ManualTime;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.scalatest.junit.JUnitSuite;
import java.time.Duration;

import akka.actor.typed.javadsl.Behaviors;

import org.junit.Test;

import akka.actor.testkit.typed.javadsl.TestProbe;

public class ManualTimerExampleTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(ManualTime.config());

  private final ManualTime manualTime = ManualTime.get(testKit.system());

  static final class Tick {}

  static final class Tock {}

  @Test
  public void testScheduleNonRepeatedTicks() {
    TestProbe<Tock> probe = testKit.createTestProbe();
    Behavior<Tick> behavior =
        Behaviors.withTimers(
            timer -> {
              timer.startSingleTimer("T", new Tick(), Duration.ofMillis(10));
              return Behaviors.receive(
                  (context, tick) -> {
                    probe.ref().tell(new Tock());
                    return Behaviors.same();
                  });
            });

    testKit.spawn(behavior);

    manualTime.expectNoMessageFor(Duration.ofMillis(9), probe);

    manualTime.timePasses(Duration.ofMillis(2));
    probe.expectMessageClass(Tock.class);

    manualTime.expectNoMessageFor(Duration.ofSeconds(10), probe);
  }
}
// #manual-scheduling-simple
