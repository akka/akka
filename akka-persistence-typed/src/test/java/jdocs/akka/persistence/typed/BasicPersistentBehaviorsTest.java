/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class BasicPersistentBehaviorsTest {

  //#structure
  public interface Command {}
  public interface Event {}
  public static class State {}

  public static class MyPersistentBehavior extends PersistentBehavior<Command, Event, State> {

    public MyPersistentBehavior(String persistenceId) {
      super(persistenceId);
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
      return (ctx, command) -> Effect().none();
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler(State state) {
      return commandHandler();
    }

    @Override
    public EventHandler<Event, State> eventHandler() {
      return eventHandler(new State());
    }

    @Override
    public EventHandler<Event, State> eventHandler(State state) {
      return event -> state;
    }

    //#recovery
    @Override
    public void onRecoveryCompleted(ActorContext<Command> ctx, Optional<State> stateOpt) {
      // called once recovery is completed
    }
    //#recovery

    //#tagging
    @Override
    public Set<String> tagsFor(Event event) {
      // inspect the event and decide if it should be tagged
      return Collections.emptySet();
    }
    //#tagging
  }

  static Behavior<Command> persistentBehavior = new MyPersistentBehavior("pid");
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
