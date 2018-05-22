/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.javadsl.*;

import java.util.Collections;
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
    public State initialState() {
      return new State();
    }

    @Override
    public CommandHandler<Command, Event, State> commandHandler() {
      return (ctx, state, command) -> Effect().none();
    }

    @Override
    public EventHandler<Event, State> eventHandler() {
      return (state, event) -> state;
    }

    //#recovery
    @Override
    public void onRecoveryCompleted(PersistentActorContext<Command> ctx, State state) {
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
