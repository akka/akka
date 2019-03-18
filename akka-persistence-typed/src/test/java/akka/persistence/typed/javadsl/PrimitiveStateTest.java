/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.RecoveryCompleted;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

public class PrimitiveStateTest extends JUnitSuite {

  private static final Config config =
      ConfigFactory.parseString(
          "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  static class PrimitiveState extends EventSourcedBehavior<Integer, Integer, Integer> {

    private final ActorRef<String> probe;

    PrimitiveState(PersistenceId persistenceId, ActorRef<String> probe) {
      super(persistenceId);
      this.probe = probe;
    }

    @Override
    public Integer emptyState() {
      return 0;
    }

    @Override
    public SignalHandler signalHandler() {
      return newSignalHandlerBuilder()
          .onSignal(
              RecoveryCompleted.class,
              (completed) -> {
                probe.tell("onRecoveryCompleted:" + completed.getState());
              })
          .build();
    }

    @Override
    public CommandHandler<Integer, Integer, Integer> commandHandler() {
      return (state, command) -> {
        if (command < 0) return Effect().stop();
        else return Effect().persist(command);
      };
    }

    @Override
    public EventHandler<Integer, Integer> eventHandler() {
      return (state, event) -> {
        probe.tell("eventHandler:" + state + ":" + event);
        return state + event;
      };
    }
  }

  @Test
  public void handleIntegerState() throws Exception {
    TestProbe<String> probe = testKit.createTestProbe();
    Behavior<Integer> b =
        Behaviors.setup(ctx -> new PrimitiveState(new PersistenceId("a"), probe.ref()));
    ActorRef<Integer> ref1 = testKit.spawn(b);
    probe.expectMessage("onRecoveryCompleted:0");
    ref1.tell(1);
    probe.expectMessage("eventHandler:0:1");
    ref1.tell(2);
    probe.expectMessage("eventHandler:1:2");

    ref1.tell(-1);
    ActorRef<Integer> ref2 = testKit.spawn(b);
    // eventHandler from reply
    probe.expectMessage("eventHandler:0:1");
    probe.expectMessage("eventHandler:1:2");
    probe.expectMessage("onRecoveryCompleted:3");
    ref2.tell(3);
    probe.expectMessage("eventHandler:3:3");
  }
}
