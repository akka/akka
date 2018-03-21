/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.javadsl;

//#manual-scheduling-simple
import java.util.concurrent.TimeUnit;

import akka.actor.typed.Behavior;
import akka.testkit.typed.javadsl.ManualTime;
import akka.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import akka.actor.typed.javadsl.Behaviors;

import org.junit.Test;

import akka.testkit.typed.javadsl.TestProbe;

public class ManualTimerExampleTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(ManualTime.config());

  private final ManualTime manualTime = ManualTime.get(testKit.system());

  static final class Tick {}
  static final class Tock {}

  @Test
  public void testScheduleNonRepeatedTicks() {
    TestProbe<Tock> probe = testKit.createTestProbe();
    Behavior<Tick> behavior = Behaviors.withTimers(timer -> {
      timer.startSingleTimer("T", new Tick(), Duration.create(10, TimeUnit.MILLISECONDS));
      return Behaviors.receive( (ctx, tick) -> {
        probe.ref().tell(new Tock());
        return Behaviors.same();
      });
    });

    testKit.spawn(behavior);

    manualTime.expectNoMessageFor(Duration.create(9, TimeUnit.MILLISECONDS), probe);

    manualTime.timePasses(Duration.create(2, TimeUnit.MILLISECONDS));
    probe.expectMessageClass(Tock.class);

    manualTime.expectNoMessageFor(Duration.create(10, TimeUnit.SECONDS), probe);
  }


}
//#manual-scheduling-simple
