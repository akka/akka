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
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

public class NullEmptyStateTest extends JUnitSuite {

  private static final Config config =
      ConfigFactory.parseString(
          "akka.persistence.journal.plugin = \"akka.persistence.journal.inmem\" \n");

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource(config);

  static class NullEmptyState extends EventSourcedBehavior<String, String, String> {

    private final ActorRef<String> probe;

    NullEmptyState(PersistenceId persistenceId, ActorRef<String> probe) {
      super(persistenceId);
      this.probe = probe;
    }

    @Override
    public String emptyState() {
      return null;
    }

    @Override
    public void onRecoveryCompleted(String s) {
      probe.tell("onRecoveryCompleted:" + s);
    }

    @Override
    public CommandHandler<String, String, String> commandHandler() {

      return newCommandHandlerBuilder()
          .forAnyState()
          .onCommand("stop"::equals, command -> Effect().stop())
          .onCommand(String.class, this::persistCommand)
          .build();
    }

    private Effect<String, String> persistCommand(String command) {
      return Effect().persist(command);
    }

    @Override
    public EventHandler<String, String> eventHandler() {
      return newEventHandlerBuilder().forAnyState().onEvent(String.class, this::applyEvent).build();
    }

    private String applyEvent(String state, String event) {
      probe.tell("eventHandler:" + state + ":" + event);
      if (state == null) return event;
      else return state + event;
    }
  }

  @Test
  public void handleNullState() throws Exception {
    TestProbe<String> probe = testKit.createTestProbe();
    Behavior<String> b =
        Behaviors.setup(ctx -> new NullEmptyState(new PersistenceId("a"), probe.ref()));

    ActorRef<String> ref1 = testKit.spawn(b);
    probe.expectMessage("onRecoveryCompleted:null");
    ref1.tell("stop");

    ActorRef<String> ref2 = testKit.spawn(b);
    probe.expectMessage("onRecoveryCompleted:null");
    ref2.tell("one");
    probe.expectMessage("eventHandler:null:one");
    ref2.tell("two");
    probe.expectMessage("eventHandler:one:two");

    ref2.tell("stop");
    ActorRef<String> ref3 = testKit.spawn(b);
    // eventHandler from reply
    probe.expectMessage("eventHandler:null:one");
    probe.expectMessage("eventHandler:one:two");
    probe.expectMessage("onRecoveryCompleted:onetwo");
    ref3.tell("three");
    probe.expectMessage("eventHandler:onetwo:three");
  }
}
