/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.DeleteEventsFailed;
import akka.persistence.typed.DeleteSnapshotsFailed;
import akka.persistence.typed.RecoveryCompleted;
import akka.persistence.typed.SnapshotFailed;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
// #behavior
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import akka.persistence.typed.PersistenceId;

// #behavior
import akka.persistence.typed.javadsl.RetentionCriteria;
import akka.persistence.typed.javadsl.SignalHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BasicPersistentBehaviorTest {

  interface Structure {
    // #structure
    public class MyPersistentBehavior
        extends EventSourcedBehavior<
            MyPersistentBehavior.Command, MyPersistentBehavior.Event, MyPersistentBehavior.State> {

      static EventSourcedBehavior<Command, Event, State> eventSourcedBehavior =
          new MyPersistentBehavior(new PersistenceId("pid"));

      interface Command {}

      interface Event {}

      public static class State {}

      public MyPersistentBehavior(PersistenceId persistenceId) {
        super(persistenceId);
      }

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
      public EventHandler<State, Event> eventHandler() {
        return (state, event) -> {
          throw new RuntimeException("TODO: process the event return the next state");
        };
      }
    }
    // #structure
  }

  interface FirstExample {
    // #behavior
    public class MyPersistentBehavior
        extends EventSourcedBehavior<
            MyPersistentBehavior.Command, MyPersistentBehavior.Event, MyPersistentBehavior.State> {

      // #behavior

      // #command
      interface Command {}

      public static class Add implements Command {
        public final String data;

        public Add(String data) {
          this.data = data;
        }
      }

      public enum Clear implements Command {
        INSTANCE
      }

      interface Event {}

      public static class Added implements Event {
        public final String data;

        public Added(String data) {
          this.data = data;
        }
      }

      public enum Cleared implements Event {
        INSTANCE
      }
      // #command

      // #state
      public static class State {
        private final List<String> items;

        private State(List<String> items) {
          this.items = items;
        }

        public State() {
          this.items = new ArrayList<>();
        }

        public State addItem(String data) {
          List<String> newItems = new ArrayList<>(items);
          newItems.add(0, data);
          // keep 5 items
          List<String> latest = newItems.subList(0, Math.min(4, newItems.size() - 1));
          return new State(latest);
        }
      }
      // #state

      // #behavior
      public MyPersistentBehavior(PersistenceId persistenceId) {
        super(persistenceId);
      }

      @Override
      public State emptyState() {
        return new State();
      }

      // #command-handler
      @Override
      public CommandHandler<Command, Event, State> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(Add.class, command -> Effect().persist(new Added(command.data)))
            .onCommand(Clear.class, command -> Effect().persist(Cleared.INSTANCE))
            .build();
      }
      // #command-handler

      // #event-handler
      @Override
      public EventHandler<State, Event> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(Added.class, (state, event) -> state.addItem(event.data))
            .onEvent(Cleared.class, () -> new State())
            .build();
      }
      // #event-handler
    }
    // #behavior

  }

  interface More {
    interface Command {}

    interface Event {}

    public static class State {}

    // #supervision
    public class MyPersistentBehavior extends EventSourcedBehavior<Command, Event, State> {
      public MyPersistentBehavior(PersistenceId persistenceId) {
        super(
            persistenceId,
            SupervisorStrategy.restartWithBackoff(
                Duration.ofSeconds(10), Duration.ofSeconds(30), 0.2));
      }
      // #supervision

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
      public EventHandler<State, Event> eventHandler() {
        return (state, event) -> {
          throw new RuntimeException("TODO: process the event return the next state");
        };
      }

      // #recovery

      @Override
      public SignalHandler signalHandler() {
        return newSignalHandlerBuilder()
            .onSignal(
                RecoveryCompleted.instance(),
                state -> {
                  throw new RuntimeException("TODO: add some end-of-recovery side-effect here");
                })
            .build();
      }
      // #recovery

      // #tagging
      @Override
      public Set<String> tagsFor(Event event) {
        throw new RuntimeException("TODO: inspect the event and return any tags it should have");
      }
      // #tagging
    }

    EventSourcedBehavior<Command, Event, State> eventSourcedBehavior =
        new MyPersistentBehavior(new PersistenceId("pid"));

    // #wrapPersistentBehavior
    Behavior<Command> debugAlwaysSnapshot =
        Behaviors.setup(
            (context) -> {
              return new MyPersistentBehavior(new PersistenceId("pid")) {
                @Override
                public boolean shouldSnapshot(State state, Event event, long sequenceNr) {
                  context
                      .getLog()
                      .info(
                          "Snapshot actor {} => state: {}", context.getSelf().path().name(), state);
                  return true;
                }
              };
            });
    // #wrapPersistentBehavior

    public static class BookingCompleted implements Event {}

    public static class Snapshotting extends EventSourcedBehavior<Command, Event, State> {
      public Snapshotting(PersistenceId persistenceId) {
        super(persistenceId);
      }

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
      public EventHandler<State, Event> eventHandler() {
        return (state, event) -> {
          throw new RuntimeException("TODO: process the event return the next state");
        };
      }

      // #snapshottingPredicate
      @Override // override shouldSnapshot in EventSourcedBehavior
      public boolean shouldSnapshot(State state, Event event, long sequenceNr) {
        return event instanceof BookingCompleted;
      }
      // #snapshottingPredicate

      // #retentionCriteria
      @Override // override retentionCriteria in EventSourcedBehavior
      public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2);
      }
      // #retentionCriteria

      // #retentionCriteriaWithSignals
      @Override
      public SignalHandler signalHandler() {
        return newSignalHandlerBuilder()
            .onSignal(
                SnapshotFailed.class,
                (state, completed) -> {
                  throw new RuntimeException("TODO: add some on-snapshot-failed side-effect here");
                })
            .onSignal(
                DeleteSnapshotsFailed.class,
                (state, completed) -> {
                  throw new RuntimeException(
                      "TODO: add some on-delete-snapshot-failed side-effect here");
                })
            .onSignal(
                DeleteEventsFailed.class,
                (state, completed) -> {
                  throw new RuntimeException(
                      "TODO: add some on-delete-snapshot-failed side-effect here");
                })
            .build();
      }
      // #retentionCriteriaWithSignals
    }

    public static class Snapshotting2 extends Snapshotting {
      public Snapshotting2(PersistenceId persistenceId) {
        super(persistenceId);
      }

      // #snapshotAndEventDeletes
      @Override // override retentionCriteria in EventSourcedBehavior
      public RetentionCriteria retentionCriteria() {
        return RetentionCriteria.snapshotEvery(100, 2).withDeleteEventsOnSnapshot();
      }
      // #snapshotAndEventDeletes
    }
  }

  interface WithActorContext {
    interface Command {}

    interface Event {}

    public static class State {}

    // #actor-context
    public class MyPersistentBehavior extends EventSourcedBehavior<Command, Event, State> {

      public static Behavior<Command> behavior(PersistenceId persistenceId) {
        return Behaviors.setup(ctx -> new MyPersistentBehavior(persistenceId, ctx));
      }

      // this makes the context available to the command handler etc.
      private final ActorContext<Command> ctx;

      public MyPersistentBehavior(PersistenceId persistenceId, ActorContext<Command> ctx) {
        super(persistenceId);
        this.ctx = ctx;
      }

      // #actor-context
      @Override
      public State emptyState() {
        return null;
      }

      @Override
      public CommandHandler<Command, Event, State> commandHandler() {
        return null;
      }

      @Override
      public EventHandler<State, Event> eventHandler() {
        return null;
      }
      // #actor-context
    }
    // #actor-context
  }
}
