/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed;

//#manual-scheduling-simple
import java.util.concurrent.TimeUnit;

import akka.testkit.typed.javadsl.ManualTime;
import akka.testkit.typed.javadsl.TestKitJunitResource;
import org.junit.ClassRule;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.duration.Duration;

import akka.actor.typed.javadsl.Behaviors;

import org.junit.Test;

import akka.testkit.typed.javadsl.TestProbe;

public class ManualTimerTest extends JUnitSuite {

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(
    ManualTimerTest.class,
    ManualTime.config());

  private final ManualTime manualTime = ManualTime.get(testKit.system());

  static final class Tick {}
  static final class Tock {}

  @Test
  public void testScheduleNonRepeatedTicks() {
    TestProbe<Tock> probe = testKit.createTestProbe();
    Behavior<Tick> behavior = Behaviors.withTimers(timer -> {
      timer.startSingleTimer("T", new Tick(), Duration.create(10, TimeUnit.MILLISECONDS));
      return Behaviors.immutable( (ctx, tick) -> {
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
