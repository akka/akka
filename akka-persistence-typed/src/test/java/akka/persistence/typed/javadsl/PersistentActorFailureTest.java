/** Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com> */
package akka.persistence.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.time.Duration;

import static akka.persistence.typed.scaladsl.PersistentBehaviorFailureSpec.conf;

class FailingPersistentActor extends PersistentBehavior<String, String, String> {

  private final ActorRef<String> probe;

  FailingPersistentActor(String persistenceId, ActorRef<String> probe) {
    super(
        persistenceId,
        SupervisorStrategy.restartWithBackoff(Duration.ofMillis(1), Duration.ofMillis(5), 0.1));
    this.probe = probe;
  }

  @Override
  public void onRecoveryCompleted(String s) {
    probe.tell("starting");
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

public class PersistentActorFailureTest extends JUnitSuite {

  public static final Config config = conf().withFallback(ConfigFactory.load());

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  public static Behavior<String> fail(String pid, ActorRef<String> probe) {
    return new FailingPersistentActor(pid, probe);
  }

  @Test
  public void persistEvents() throws Exception {
    TestProbe<String> probe = testKit.createTestProbe();
    Behavior<String> p1 = fail("fail-first-2", probe.ref());
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
