/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #oo-style
// #fun-style
import akka.Done;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
// #fun-style
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
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

  interface FunctionalStyleSetupParams1 {
    // #fun-style-setup-params1

    // this is an anti-example, better solutions exists
    public class Counter {
      public interface Command {}

      public static class IncrementRepeatedly implements Command {
        public final Duration interval;

        public IncrementRepeatedly(Duration interval) {
          this.interval = interval;
        }
      }

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

      public static Behavior<Command> create(String name) {
        return Behaviors.setup(
            context -> Behaviors.withTimers(timers -> counter(name, context, timers, 0)));
      }

      private static Behavior<Command> counter(
          final String name,
          final ActorContext<Command> context,
          final TimerScheduler<Command> timers,
          final int n) {

        return Behaviors.receive(Command.class)
            .onMessage(
                IncrementRepeatedly.class,
                command -> onIncrementRepeatedly(name, context, timers, n, command))
            .onMessage(Increment.class, notUsed -> onIncrement(name, context, timers, n))
            .onMessage(GetValue.class, command -> onGetValue(n, command))
            .build();
      }

      private static Behavior<Command> onIncrementRepeatedly(
          String name,
          ActorContext<Command> context,
          TimerScheduler<Command> timers,
          int n,
          IncrementRepeatedly command) {
        context
            .getLog()
            .debug(
                "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                name,
                command.interval,
                n);
        timers.startTimerWithFixedDelay("repeat", Increment.INSTANCE, command.interval);
        return Behaviors.same();
      }

      private static Behavior<Command> onIncrement(
          String name, ActorContext<Command> context, TimerScheduler<Command> timers, int n) {
        int newValue = n + 1;
        context.getLog().debug("[{}] Incremented counter to [{}]", name, newValue);
        return counter(name, context, timers, newValue);
      }

      private static Behavior<Command> onGetValue(int n, GetValue command) {
        command.replyTo.tell(new Value(n));
        return Behaviors.same();
      }
    }
    // #fun-style-setup-params1
  }

  interface FunctionalStyleSetupParams2 {
    // #fun-style-setup-params2

    // this is better than previous example, but even better solution exists
    public class Counter {
      // messages omitted for brevity, same messages as above example
      // #fun-style-setup-params2
      public interface Command {}

      public static class IncrementRepeatedly implements Command {
        public final Duration interval;

        public IncrementRepeatedly(Duration interval) {
          this.interval = interval;
        }
      }

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
      // #fun-style-setup-params2

      private static class Setup {
        final String name;
        final ActorContext<Command> context;
        final TimerScheduler<Command> timers;

        private Setup(String name, ActorContext<Command> context, TimerScheduler<Command> timers) {
          this.name = name;
          this.context = context;
          this.timers = timers;
        }
      }

      public static Behavior<Command> create(String name) {
        return Behaviors.setup(
            context ->
                Behaviors.withTimers(timers -> counter(new Setup(name, context, timers), 0)));
      }

      private static Behavior<Command> counter(final Setup setup, final int n) {

        return Behaviors.receive(Command.class)
            .onMessage(
                IncrementRepeatedly.class, command -> onIncrementRepeatedly(setup, n, command))
            .onMessage(Increment.class, notUsed -> onIncrement(setup, n))
            .onMessage(GetValue.class, command -> onGetValue(n, command))
            .build();
      }

      private static Behavior<Command> onIncrementRepeatedly(
          Setup setup, int n, IncrementRepeatedly command) {
        setup
            .context
            .getLog()
            .debug(
                "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                setup.name,
                command.interval,
                n);
        setup.timers.startTimerWithFixedDelay("repeat", Increment.INSTANCE, command.interval);
        return Behaviors.same();
      }

      private static Behavior<Command> onIncrement(Setup setup, int n) {
        int newValue = n + 1;
        setup.context.getLog().debug("[{}] Incremented counter to [{}]", setup.name, newValue);
        return counter(setup, newValue);
      }

      private static Behavior<Command> onGetValue(int n, GetValue command) {
        command.replyTo.tell(new Value(n));
        return Behaviors.same();
      }
    }
    // #fun-style-setup-params2
  }

  interface FunctionalStyleSetupParams3 {
    // #fun-style-setup-params3

    // this is better than previous examples
    public class Counter {
      // messages omitted for brevity, same messages as above example
      // #fun-style-setup-params3
      public interface Command {}

      public static class IncrementRepeatedly implements Command {
        public final Duration interval;

        public IncrementRepeatedly(Duration interval) {
          this.interval = interval;
        }
      }

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
      // #fun-style-setup-params3

      public static Behavior<Command> create(String name) {
        return Behaviors.setup(
            context ->
                Behaviors.withTimers(timers -> new Counter(name, context, timers).counter(0)));
      }

      private final String name;
      private final ActorContext<Command> context;
      private final TimerScheduler<Command> timers;

      private Counter(String name, ActorContext<Command> context, TimerScheduler<Command> timers) {
        this.name = name;
        this.context = context;
        this.timers = timers;
      }

      private Behavior<Command> counter(final int n) {
        return Behaviors.receive(Command.class)
            .onMessage(IncrementRepeatedly.class, command -> onIncrementRepeatedly(n, command))
            .onMessage(Increment.class, notUsed -> onIncrement(n))
            .onMessage(GetValue.class, command -> onGetValue(n, command))
            .build();
      }

      private Behavior<Command> onIncrementRepeatedly(int n, IncrementRepeatedly command) {
        context
            .getLog()
            .debug(
                "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                name,
                command.interval,
                n);
        timers.startTimerWithFixedDelay("repeat", Increment.INSTANCE, command.interval);
        return Behaviors.same();
      }

      private Behavior<Command> onIncrement(int n) {
        int newValue = n + 1;
        context.getLog().debug("[{}] Incremented counter to [{}]", name, newValue);
        return counter(newValue);
      }

      private Behavior<Command> onGetValue(int n, GetValue command) {
        command.replyTo.tell(new Value(n));
        return Behaviors.same();
      }
    }
    // #fun-style-setup-params3
  }

  interface FactoryMethod {
    // #behavior-factory-method
    public class CountDown extends AbstractBehavior<CountDown.Command> {

      public interface Command {}

      public enum Down implements Command {
        INSTANCE
      }

      // factory for the initial `Behavior`
      public static Behavior<Command> create(int countDownFrom, ActorRef<Done> notifyWhenZero) {
        return Behaviors.setup(context -> new CountDown(countDownFrom, notifyWhenZero));
      }

      private final ActorRef<Done> notifyWhenZero;
      private int remaining;

      private CountDown(int countDownFrom, ActorRef<Done> notifyWhenZero) {
        this.remaining = countDownFrom;
        this.notifyWhenZero = notifyWhenZero;
      }

      @Override
      public Receive<Command> createReceive() {
        return newReceiveBuilder().onMessage(Down.class, notUsed -> onDown()).build();
      }

      private Behavior<Command> onDown() {
        remaining--;
        if (remaining == 0) {
          notifyWhenZero.tell(Done.getInstance());
          return Behaviors.stopped();
        } else {
          return this;
        }
      }
    }
    // #behavior-factory-method

    public class Usage {
      private ActorContext<?> context = null;
      private ActorRef<Done> doneRef = null;

      {
        // #behavior-factory-method-spawn
        ActorRef<CountDown.Command> countDown =
            context.spawn(CountDown.create(100, doneRef), "countDown");
        // #behavior-factory-method-spawn
      }
    }
  }
}
