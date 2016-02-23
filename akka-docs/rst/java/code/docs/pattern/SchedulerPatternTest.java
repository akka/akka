/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.pattern;

import akka.actor.*;
import akka.testkit.*;
import akka.testkit.TestEvent.Mute;
import akka.testkit.TestEvent.UnMute;
import docs.AbstractJavaTest;
import org.junit.*;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class SchedulerPatternTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("SchedulerPatternTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  static
  //#schedule-constructor
  public class ScheduleInConstructor extends UntypedActor {

    private final Cancellable tick = getContext().system().scheduler().schedule(
      Duration.create(500, TimeUnit.MILLISECONDS),
      Duration.create(1000, TimeUnit.MILLISECONDS),
      getSelf(), "tick", getContext().dispatcher(), null);
    //#schedule-constructor
    // this variable and constructor is declared here to not show up in the docs
    final ActorRef target;
    public ScheduleInConstructor(ActorRef target) {
      this.target = target;
    }
    //#schedule-constructor

    @Override
    public void postStop() {
      tick.cancel();
    }

    @Override
    public void onReceive(Object message) throws Exception {
      if (message.equals("tick")) {
        // do something useful here
        //#schedule-constructor
        target.tell(message, getSelf());
        //#schedule-constructor
      }
      //#schedule-constructor
      else if (message.equals("restart")) {
        throw new ArithmeticException();
      }
      //#schedule-constructor
      else {
        unhandled(message);
      }
    }
  }
  //#schedule-constructor

  static
  //#schedule-receive
  public class ScheduleInReceive extends UntypedActor {
    //#schedule-receive
    // this variable and constructor is declared here to not show up in the docs
    final ActorRef target;
    public ScheduleInReceive(ActorRef target) {
      this.target = target;
    }
    //#schedule-receive

    @Override
    public void preStart() {
      getContext().system().scheduler().scheduleOnce(
        Duration.create(500, TimeUnit.MILLISECONDS),
        getSelf(), "tick", getContext().dispatcher(), null);
    }

    // override postRestart so we don't call preStart and schedule a new message
    @Override
    public void postRestart(Throwable reason) {
    }

    @Override
    public void onReceive(Object message) throws Exception {
      if (message.equals("tick")) {
        // send another periodic tick after the specified delay
        getContext().system().scheduler().scheduleOnce(
          Duration.create(1000, TimeUnit.MILLISECONDS),
          getSelf(), "tick", getContext().dispatcher(), null);
        // do something useful here
        //#schedule-receive
        target.tell(message, getSelf());
        //#schedule-receive
      }
      //#schedule-receive
      else if (message.equals("restart")) {
        throw new ArithmeticException();
      }
      //#schedule-receive
      else {
        unhandled(message);
      }
    }
  }
  //#schedule-receive

  @Test
  @Ignore // no way to tag this as timing sensitive
  public void scheduleInConstructor() {
    new TestSchedule(system) {{
      final JavaTestKit probe = new JavaTestKit(system);
      final Props props = Props.create(ScheduleInConstructor.class, probe.getRef());
      testSchedule(probe, props, duration("3000 millis"), duration("2000 millis"));
    }};
  }

  @Test
  @Ignore // no way to tag this as timing sensitive
  public void scheduleInReceive() {
    new TestSchedule(system) {{
      final JavaTestKit probe = new JavaTestKit(system);
      final Props props = Props.create(ScheduleInReceive.class, probe.getRef());
      testSchedule(probe, props, duration("3000 millis"), duration("2500 millis"));
    }};
  }

  public static class TestSchedule extends JavaTestKit {
    private ActorSystem system;

    public TestSchedule(ActorSystem system) {
      super(system);
      this.system = system;
    }

    public void testSchedule(final JavaTestKit probe, Props props,
                             FiniteDuration startDuration,
                             FiniteDuration afterRestartDuration) {
      Iterable<akka.testkit.EventFilter> filter =
        Arrays.asList(new akka.testkit.EventFilter[]{
          (akka.testkit.EventFilter) new ErrorFilter(ArithmeticException.class)});
      try {
        system.eventStream().publish(new Mute(filter));

        final ActorRef actor = system.actorOf(props);
        new Within(startDuration) {
          protected void run() {
            probe.expectMsgEquals("tick");
            probe.expectMsgEquals("tick");
            probe.expectMsgEquals("tick");
          }
        };
        actor.tell("restart", getRef());
        new Within(afterRestartDuration) {
          protected void run() {
            probe.expectMsgEquals("tick");
            probe.expectMsgEquals("tick");
          }
        };
        system.stop(actor);
      }
      finally {
        system.eventStream().publish(new UnMute(filter));
      }
    }
  }
}
