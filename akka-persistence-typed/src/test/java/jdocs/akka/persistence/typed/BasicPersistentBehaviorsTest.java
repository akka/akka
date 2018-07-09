/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;
import java.time.Duration;

import java.util.Collections;
import java.util.Set;

public class BasicPersistentBehaviorsTest {

  //#structure
  public interface Command {}
  public interface Event {}
  public static class State {}

  //#supervision
  public static class MyPersistentBehavior extends PersistentBehavior<Command, Event, State> {
    public MyPersistentBehavior(String persistenceId) {
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
    public EventHandler<Event, State> eventHandler() {
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

  static PersistentBehavior<Command, Event, State> persistentBehavior = new MyPersistentBehavior("pid");
  //#structure

  //#wrapPersistentBehavior
  static Behavior<Command> debugAlwaysSnapshot = Behaviors.setup((context) -> {
            return new MyPersistentBehavior("pid") {
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
