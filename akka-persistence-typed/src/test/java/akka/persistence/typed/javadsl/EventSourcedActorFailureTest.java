/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.testkit.typed.TestException;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Signal;
import akka.actor.typed.SupervisorStrategy;
import akka.japi.function.Effect;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.RecoveryCompleted;
import akka.persistence.typed.RecoveryFailed;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.time.Duration;

import static akka.persistence.typed.scaladsl.EventSourcedBehaviorFailureSpec.conf;

class FailingEventSourcedActor extends EventSourcedBehavior<String, String, String> {

  private final ActorRef<String> probe;
  private final ActorRef<Throwable> recoveryFailureProbe;

  FailingEventSourcedActor(
      PersistenceId persistenceId,
      ActorRef<String> probe,
      ActorRef<Throwable> recoveryFailureProbe) {

    super(
        persistenceId,
        SupervisorStrategy.restartWithBackoff(Duration.ofMillis(1), Duration.ofMillis(5), 0.1));
    this.probe = probe;
    this.recoveryFailureProbe = recoveryFailureProbe;
  }

  @Override
  public SignalHandler signalHandler() {
    return newSignalHandlerBuilder()
        .onSignal(
            RecoveryCompleted.class,
            (recoveryCompleted) -> {
              probe.tell("starting");
            })
        .onSignal(
            RecoveryFailed.class,
            (signal) -> {
              recoveryFailureProbe.tell(signal.getFailure());
            })
        .build();
  }

  @Override
  public String emptyState() {
    return "";
  }

  @Override
  public CommandHandler<String, String, String> commandHandler() {
    return (state, command) -> {
      probe.tell("persisting");
      return Effect().persist(command);
    };
  }

  @Override
  public EventHandler<String, String> eventHandler() {
    return (state, event) -> {
      probe.tell(event);
      return state + event;
    };
  }
}

public class EventSourcedActorFailureTest extends JUnitSuite {

  public static final Config config = conf().withFallback(ConfigFactory.load());

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  public static Behavior<String> fail(
      PersistenceId pid, ActorRef<String> probe, ActorRef<Throwable> recoveryFailureProbe) {
    return new FailingEventSourcedActor(pid, probe, recoveryFailureProbe);
  }

  public static Behavior<String> fail(PersistenceId pid, ActorRef<String> probe) {
    return fail(pid, probe, testKit.<Throwable>createTestProbe().ref());
  }

  public EventSourcedActorFailureTest() {
    // FIXME ##24348 silence logging in a proper way
    akka.actor.typed.javadsl.Adapter.toUntyped(testKit.system())
        .eventStream()
        .publish(
            new TestEvent.Mute(
                akka.japi.Util.immutableSeq(
                    new EventFilter[] {
                      EventFilter.warning(null, null, "No default snapshot store", null, 1)
                    })));
    akka.actor.typed.javadsl.Adapter.toUntyped(testKit.system())
        .eventStream()
        .publish(
            new TestEvent.Mute(
                akka.japi.Util.immutableSeq(
                    new EventFilter[] {EventFilter.error(null, null, "", ".*saw failure.*", 1)})));
  }

  @Test
  public void notifyRecoveryFailure() {
    TestProbe<String> probe = testKit.createTestProbe();
    TestProbe<Throwable> recoveryFailureProbe = testKit.createTestProbe();
    Behavior<String> p1 =
        fail(new PersistenceId("fail-recovery-once"), probe.ref(), recoveryFailureProbe.ref());
    testKit.spawn(p1);
    recoveryFailureProbe.expectMessageClass(TestException.class);
  }

  @Test
  public void persistEvents() throws Exception {
    TestProbe<String> probe = testKit.createTestProbe();
    Behavior<String> p1 = fail(new PersistenceId("fail-first-2"), probe.ref());
    ActorRef<String> c = testKit.spawn(p1);
    probe.expectMessage("starting");
    // fail
    c.tell("one");
    probe.expectMessage("persisting");
    probe.expectMessage("one");
    probe.expectMessage("starting");
    // fail
    c.tell("two");
    probe.expectMessage("persisting");
    probe.expectMessage("two");
    probe.expectMessage("starting");
    // work
    c.tell("three");
    probe.expectMessage("persisting");
    probe.expectMessage("three");
    // no starting as this one did not fail
    probe.expectNoMessage();
  }
}
