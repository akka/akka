/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

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
      return new CommandHandler<Command, Event, State>() {
        @Override
        public Effect<Event, State> apply(ActorContext<Command> ctx, State state, Command command) {
          return Effect().none();
        }
      };
    }

    @Override
    public EventHandler<Event, State> eventHandler() {
      return new EventHandler<Event, State>() {
        @Override
        public State apply(State state, Event event) {
          return state;
        }
      };
    }

    //#recovery
    @Override
    public void onRecoveryCompleted(ActorContext<Command> ctx, State state) {
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
}
