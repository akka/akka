/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Behaviors;
import akka.japi.Pair;
import akka.japi.function.Function3;
import akka.persistence.typed.scaladsl.PersistentActorSpec;
import akka.testkit.typed.TestKit;
import akka.testkit.typed.javadsl.TestProbe;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class PersistentActorTest extends TestKit {

  static final Incremented timeoutEvent = new Incremented(100);
  static final State emptyState = new State(0, Collections.emptyList());
  static final Incremented terminatedEvent = new Incremented(10);

  public PersistentActorTest() {
    super(PersistentActorSpec.config());
  }

  interface Command {
  }

  public static class Increment implements Command {
    private Increment() {
    }

    public static Increment instance = new Increment();
  }

  public static class Increment100OnTimeout implements Command {
    private Increment100OnTimeout() {
    }

    public static Increment100OnTimeout instance = new Increment100OnTimeout();
  }

  static class IncrementLater implements Command {
  }

  static class DelayFinished implements Command {
  }

  static class EmptyEventsListAndThenLog implements Command {
  }

  static class IncrementTwiceAndLog implements Command {
  }

  static class StopThenLog implements Command {
  }

  public static class Timeout implements Command {
  }

  public static class GetValue implements Command {
    private final ActorRef<State> replyTo;

    public GetValue(ActorRef<State> replyTo) {
      this.replyTo = replyTo;
    }
  }

  public static class Incremented {
    private final int delta;

    public Incremented(int delta) {
      this.delta = delta;
    }

    @Override
    public String toString() {
      return "Incremented{" +
        "delta=" + delta +
        '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Incremented that = (Incremented) o;
      return delta == that.delta;
    }

    @Override
    public int hashCode() {

      return Objects.hash(delta);
    }
  }

  public static class State {
    private final int value;
    private final List<Integer> history;

    public State(int value, List<Integer> history) {
      this.value = value;
      this.history = history;
    }

    @Override
    public String toString() {
      return "State{" +
        "value=" + value +
        ", history=" + history +
        '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      State state = (State) o;
      return value == state.value &&
        Objects.equals(history, state.history);
    }

    @Override
    public int hashCode() {

      return Objects.hash(value, history);
    }
  }

  public static class Tick {
    private Tick() {
    }

    public static Tick instance = new Tick();
  }

  private static String loggingOne = "one";


  private PersistentBehavior<Command, Incremented, State> counter(String persistenceId, ActorRef<Pair<State, Incremented>> probe) {
    ActorRef<String> loggingProbe = TestProbe.create(String.class, system()).ref();
    return counter(persistenceId, probe, loggingProbe, (s, i, l) -> false);
  }

  private PersistentBehavior<Command, Incremented, State> counter(String persistenceId) {
    return counter(persistenceId,
      TestProbe.<Pair<State, Incremented>>create(system()).ref(),
      TestProbe.<String>create(system()).ref(),
      (s, i, l) -> false);
  }

  private PersistentBehavior<Command, Incremented, State> counter(
    String persistenceId,
    Function3<State, Incremented, Long, Boolean> snapshot
  ) {
    return counter(persistenceId,
      TestProbe.<Pair<State, Incremented>>create(system()).ref(),
      TestProbe.<String>create(system()).ref(), snapshot);
  }

  private PersistentBehavior<Command, Incremented, State> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    ActorRef<String> loggingProbe) {
    return counter(persistentId, eventProbe, loggingProbe, (s, i, l) -> false);
  }

  private PersistentBehavior<Command, Incremented, State> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    Function3<State, Incremented, Long, Boolean> snapshot) {
    return counter(persistentId, eventProbe, TestProbe.<String>create(system()).ref(), snapshot);
  }

  private PersistentBehavior<Command, Incremented, State> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    ActorRef<String> loggingProbe,
    Function3<State, Incremented, Long, Boolean> snapshot) {
    return new PersistentBehavior<Command, Incremented, State>(persistentId) {
      @Override
      public CommandHandler<Command, Incremented, State> commandHandler() {
        return commandHandlerBuilder()
          .matchCommand(Increment.class, (ctx, state, command) -> Effect().persist(new Incremented(1)))
          .matchCommand(GetValue.class, (ctx, state, command) -> {
            command.replyTo.tell(state);
            return Effect().none();
          })
          .matchCommand(IncrementLater.class, (ctx, state, command) -> {
            ActorRef<Object> delay = ctx.spawnAnonymous(Behaviors.withTimers(timers -> {
              timers.startSingleTimer(Tick.instance, Tick.instance, FiniteDuration.create(10, TimeUnit.MILLISECONDS));
              return Behaviors.immutable((context, o) -> Behaviors.stopped());
            }));
            ctx.watchWith(delay, new DelayFinished());
            return Effect().none();
          })
          .matchCommand(DelayFinished.class, (ctx, state, finished) -> Effect().persist(new Incremented(10)))
          .matchCommand(Increment100OnTimeout.class, (ctx, state, msg) -> {
            ctx.setReceiveTimeout(FiniteDuration.create(10, TimeUnit.MILLISECONDS), new Timeout());
            return Effect().none();
          })
          .matchCommand(Timeout.class,
            (ctx, state, msg) -> Effect().persist(timeoutEvent))
          .matchCommand(EmptyEventsListAndThenLog.class, (ctx, state, msg) -> Effect().persist(Collections.emptyList())
            .andThen(s -> loggingProbe.tell(loggingOne)))
          .matchCommand(StopThenLog.class,
            (ctx, state, msg) -> Effect().stop()
              .andThen(s -> loggingProbe.tell(loggingOne)))
          .matchCommand(IncrementTwiceAndLog.class,
            (ctx, state, msg) -> Effect().persist(
              Arrays.asList(new Incremented(1), new Incremented(1)))
              .andThen(s -> loggingProbe.tell(loggingOne)))
          .build();

      }

      @Override
      public EventHandler<Incremented, State> eventHandler() {
        return eventHandlerBuilder()
          .matchEvent(Incremented.class, (state, event) -> {
            List<Integer> newHistory = new ArrayList<>(state.history);
            newHistory.add(state.value);
            eventProbe.tell(Pair.create(state, event));
            return new State(state.value + event.delta, newHistory);
          })
          .build();

      }

      @Override
      public State initialState() {
        return emptyState;
      }

      @Override
      public boolean shouldSnapshot(State state, Incremented event, long sequenceNr) {
        try {
          return snapshot.apply(state, event, sequenceNr);
        } catch (Exception e) {
          return false;
        }
      }
    };
  }

  @Test
  public void persistEvents() {
    ActorRef<Command> c = spawn(counter("c2"));
    TestProbe<State> probe = TestProbe.create(system());
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }

  @Test
  public void replyStoredEvents() {
    ActorRef<Command> c = spawn(counter("c2"));
    TestProbe<State> probe = TestProbe.create(system());
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    ActorRef<Command> c2 = spawn(counter("c2"));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
    c2.tell(Increment.instance);
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(4, Arrays.asList(0, 1, 2, 3)));
  }

  @Test
  public void handleTerminatedSignal() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = TestProbe.create(system());
    ActorRef<Command> c = spawn(counter("c2", eventHandlerProbe.ref()));
    c.tell(Increment.instance);
    c.tell(new IncrementLater());
    eventHandlerProbe.expectMessage(Pair.create(emptyState, new Incremented(1)));
    eventHandlerProbe.expectMessage(Pair.create(new State(1, Collections.singletonList(0)), terminatedEvent));
  }

  @Test
  public void handleReceiveTimeout() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = TestProbe.create(system());
    ActorRef<Command> c = spawn(counter("c1", eventHandlerProbe.ref()));
    c.tell(new Increment100OnTimeout());
    eventHandlerProbe.expectMessage(Pair.create(emptyState, timeoutEvent));
  }

  @Test
  public void chainableSideEffectsWithEvents() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = TestProbe.create(system());
    TestProbe<String> loggingProbe = TestProbe.create(system());
    ActorRef<Command> c = spawn(counter("c1", eventHandlerProbe.ref(), loggingProbe.ref()));
    c.tell(new EmptyEventsListAndThenLog());
    loggingProbe.expectMessage(loggingOne);
  }

  @Test
  public void snapshot() {
    PersistentBehavior<Command, Incremented, State> snapshoter = counter("c11", (s, e, l) -> s.value % 2 == 0);
    ActorRef<Command> c = spawn(snapshoter);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    TestProbe<State> probe = TestProbe.create(system());
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    TestProbe<Pair<State, Incremented>> eventProbe = TestProbe.create(system());
    snapshoter = counter("c11", eventProbe.ref(), (s, e, l) -> s.value % 2 == 0);
    ActorRef<Command> c2 = spawn(snapshoter);
    // First 2 are snapshot
    eventProbe.expectMessage(Pair.create(new State(2, Arrays.asList(0, 1)), new Incremented(1)));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
  }

  @Test
  public void stopThenLog() {
    TestProbe<State> probe = TestProbe.create(system());
    ActorRef<Command> c = spawn(counter("c12"));
    c.tell(new StopThenLog());
    probe.expectTerminated(c, FiniteDuration.create(1, TimeUnit.SECONDS));
  }
  // FIXME test with by state command handler
}
