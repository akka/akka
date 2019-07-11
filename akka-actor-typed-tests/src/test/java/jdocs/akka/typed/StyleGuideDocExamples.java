/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #oo-style
// #fun-style
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
// #fun-style
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Receive;
// #oo-style

interface StyleGuideDocExamples {

  interface FunctionalStyle {

    // #fun-style

    public class Counter {
      public interface Command {}

      public enum Increment implements Command {
        INSTANCE
      }

      public static class GetValue implements Command {
        public final ActorRef<Value> replyTo;

        public GetValue(ActorRef<Value> replyTo) {
          this.replyTo = replyTo;
        }
      }

      public static class Value {
        public final int value;

        public Value(int value) {
          this.value = value;
        }
      }

      public static Behavior<Command> create() {
        return Behaviors.setup(context -> counter(context, 0));
      }

      private static Behavior<Command> counter(final ActorContext<Command> context, final int n) {

        return Behaviors.receive(Command.class)
            .onMessage(Increment.class, notUsed -> onIncrement(context, n))
            .onMessage(GetValue.class, command -> onGetValue(n, command))
            .build();
      }

      private static Behavior<Command> onIncrement(ActorContext<Command> context, int n) {
        int newValue = n + 1;
        context.getLog().debug("Incremented counter to [{}]", newValue);
        return counter(context, newValue);
      }

      private static Behavior<Command> onGetValue(int n, GetValue command) {
        command.replyTo.tell(new Value(n));
        return Behaviors.same();
      }
    }
    // #fun-style
  }

  interface OOStyle {

    // #oo-style
    public class Counter extends AbstractBehavior<Counter.Command> {

      public interface Command {}

      public enum Increment implements Command {
        INSTANCE
      }

      public static class GetValue implements Command {
        public final ActorRef<Value> replyTo;

        public GetValue(ActorRef<Value> replyTo) {
          this.replyTo = replyTo;
        }
      }

      public static class Value {
        public final int value;

        public Value(int value) {
          this.value = value;
        }
      }

      public static Behavior<Command> create() {
        return Behaviors.setup(Counter::new);
      }

      private final ActorContext<Counter.Command> context;

      private int n;

      private Counter(ActorContext<Command> context) {
        this.context = context;
      }

      @Override
      public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Increment.class, notUsed -> onIncrement())
            .onMessage(GetValue.class, this::onGetValue)
            .build();
      }

      private Behavior<Command> onIncrement() {
        n++;
        context.getLog().debug("Incremented counter to [{}]", n);
        return this;
      }

      private Behavior<Command> onGetValue(GetValue command) {
        command.replyTo.tell(new Value(n));
        return this;
      }
    }
    // #oo-style

  }
}
