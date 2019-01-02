/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import java.time.Duration;

import java.util.Set;

public class BasicPersistentBehaviorTest {

  //#structure
  public interface Command {}
  public interface Event {}
  public static class State {}

  //#supervision
  public static class MyPersistentBehavior extends EventSourcedBehavior<Command, Event, State> {
    public MyPersistentBehavior(PersistenceId persistenceId) {
      super(persistenceId, SupervisorStrategy.restartWithBackoff(Duration.ofSeconds(10), Duration.ofSeconds(30), 0.2));
    }
    //#supervision

    @Override
    public State emptyState() {
      return new State();
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
      return (state, command) -> {
        throw new RuntimeException("TODO: process the command & return an Effect");
      };
    }

    @Override
    public EventHandler<State, Event>  eventHandler() {
      return (state, event) -> {
        throw new RuntimeException("TODO: process the event return the next state");
      };
    }

    //#recovery
    @Override
    public void onRecoveryCompleted(State state) {
      throw new RuntimeException("TODO: add some end-of-recovery side-effect here");
    }
    //#recovery

    //#tagging
    @Override
    public Set<String> tagsFor(Event event) {
      throw new RuntimeException("TODO: inspect the event and return any tags it should have");
    }
    //#tagging
  }

  static EventSourcedBehavior<Command, Event, State> eventSourcedBehavior =
      new MyPersistentBehavior(new PersistenceId("pid"));
  //#structure

  //#wrapPersistentBehavior
  static Behavior<Command> debugAlwaysSnapshot = Behaviors.setup((context) -> {
            return new MyPersistentBehavior(new PersistenceId("pid")) {
              @Override
              public boolean shouldSnapshot(State state, Event event, long sequenceNr) {
                context.getLog().info("Snapshot actor {} => state: {}",
                        context.getSelf().path().name(), state);
                return true;
              }
            };
          }
  );
  //#wrapPersistentBehavior
}
