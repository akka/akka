/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed;

//#manual-scheduling-simple
import java.util.concurrent.TimeUnit;
import static com.typesafe.config.ConfigFactory.parseString;

import scala.concurrent.duration.Duration;

import akka.actor.typed.javadsl.Behaviors;

import org.junit.Test;

import akka.testkit.typed.TestKit;
import akka.testkit.typed.javadsl.ExplicitlyTriggeredScheduler;
import akka.testkit.typed.javadsl.TestProbe;

public class ManualTimerTest extends TestKit {
  ExplicitlyTriggeredScheduler scheduler;

  public ManualTimerTest() {
    super(parseString("akka.scheduler.implementation = \"akka.testkit.typed.javadsl.ExplicitlyTriggeredScheduler\""));
    this.scheduler = (ExplicitlyTriggeredScheduler) system().scheduler();
  }

  static final class Tick {}
  static final class Tock {}

  @Test
  public void testScheduleNonRepeatedTicks() {
    TestProbe<Tock> probe = TestProbe.create(system());
    Behavior<Tick> behavior = Behaviors.withTimers(timer -> {
      timer.startSingleTimer("T", new Tick(), Duration.create(10, TimeUnit.MILLISECONDS));
      return Behaviors.immutable( (ctx, tick) -> {
        probe.ref().tell(new Tock());
        return Behaviors.same();
      });
    });

    spawn(behavior);

    scheduler.expectNoMessageFor(Duration.create(9, TimeUnit.MILLISECONDS), probe);

    scheduler.timePasses(Duration.create(2, TimeUnit.MILLISECONDS));
    probe.expectMessageClass(Tock.class);

    scheduler.expectNoMessageFor(Duration.create(10, TimeUnit.SECONDS), probe);
  }


}
//#manual-scheduling-simple
