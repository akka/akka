/*
 * Copyright (C) 2018-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.persistence.typed.state.javadsl.CommandHandler;
// #behavior
import akka.persistence.typed.state.javadsl.DurableStateBehavior;
import akka.persistence.typed.PersistenceId;

// #behavior

//#effects
import akka.Done;
//#effects

public class DurableStatePersistentBehaviorTest {

  interface Structure {
    // #structure
    public class MyPersistentCounter
        extends DurableStateBehavior<MyPersistentCounter.Command<?>, MyPersistentCounter.State> {

      interface Command<ReplyMessage> {}

      public static class State {
        private final int value;

        public State(int value) {
          this.value = value;
        }

        public int get() {
          return value;
        }
      }

      public static Behavior<Command<?>> create(PersistenceId persistenceId) {
        return new MyPersistentCounter(persistenceId);
      }

      private MyPersistentCounter(PersistenceId persistenceId) {
        super(persistenceId);
      }

      @Override
      public State emptyState() {
        return new State(0);
      }

      @Override
      public CommandHandler<Command<?>, State> commandHandler() {
        return (state, command) -> {
          throw new RuntimeException("TODO: process the command & return an Effect");
        };
      }
    }
    // #structure
  }

  interface FirstExample {
    // #behavior
    public class MyPersistentCounter
        extends DurableStateBehavior<MyPersistentCounter.Command<?>, MyPersistentCounter.State> {

      // #behavior

      // #command
      interface Command<ReplyMessage> {}

      public enum Increment implements Command<Void> {
        INSTANCE
      }

      public static class IncrementBy implements Command<Void> {
        public final int value;

        public IncrementBy(int value) {
          this.value = value;
        }
      }

      public static class GetValue implements Command<State> {
        private final ActorRef<Integer> replyTo;

        public GetValue(ActorRef<Integer> replyTo) {
          this.replyTo = replyTo;
        }
      }

      // #command

      // #state
      public static class State {
        private final int value;

        public State(int value) {
          this.value = value;
        }

        public int get() {
          return value;
        }
      }
      // #state

      // #behavior
      // commands, events and state defined here

      public static Behavior<Command<?>> create(PersistenceId persistenceId) {
        return new MyPersistentCounter(persistenceId);
      }

      private MyPersistentCounter(PersistenceId persistenceId) {
        super(persistenceId);
      }

      @Override
      public State emptyState() {
        return new State(0);
      }

      // #command-handler
      @Override
      public CommandHandler<Command<?>, State> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(
                Increment.class, (state, command) -> Effect().persist(new State(state.get() + 1)))
            .onCommand(
                IncrementBy.class,
                (state, command) -> Effect().persist(new State(state.get() + command.value)))
            .onCommand(
                GetValue.class, (state, command) -> Effect().reply(command.replyTo, state.get()))
            .build();
      }
      // #command-handler
    }
    // #behavior

  }

  interface SecondExample {
    public class MyPersistentCounterWithReplies
        extends DurableStateBehavior<
            MyPersistentCounterWithReplies.Command<?>, MyPersistentCounterWithReplies.State> {

      // #effects
      interface Command<ReplyMessage> {}

      public static class IncrementWithConfirmation implements Command<Void> {
        public final ActorRef<Done> replyTo;

        public IncrementWithConfirmation(ActorRef<Done> replyTo) {
          this.replyTo = replyTo;
        }
      }

      public static class GetValue implements Command<State> {
        private final ActorRef<Integer> replyTo;

        public GetValue(ActorRef<Integer> replyTo) {
          this.replyTo = replyTo;
        }
      }

      public static class State {
        private final int value;

        public State(int value) {
          this.value = value;
        }

        public int get() {
          return value;
        }
      }

      public static Behavior<Command<?>> create(PersistenceId persistenceId) {
        return new MyPersistentCounterWithReplies(persistenceId);
      }

      private MyPersistentCounterWithReplies(PersistenceId persistenceId) {
        super(persistenceId);
      }

      @Override
      public State emptyState() {
        return new State(0);
      }

      @Override
      public CommandHandler<Command<?>, State> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(
                IncrementWithConfirmation.class, 
                (state, command) -> Effect().persist(new State(state.get() + 1))
                                            .thenReply(command.replyTo, (st) -> Done.getInstance()))
            .onCommand(
                GetValue.class, (state, command) -> Effect().reply(command.replyTo, state.get()))
            .build();
      }
      // #effects
    }
  }
}
