package akka.persistence.typed.javadsl;

import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Behaviors;
import akka.japi.Pair;
import akka.persistence.typed.scaladsl.PersistentActorSpec;
import akka.testkit.typed.TestKit;
import akka.testkit.typed.javadsl.TestProbe;
import org.junit.Test;
import scala.concurrent.duration.FiniteDuration;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;

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

  // FIXME factory method for the builder
  private static CommandHandler<Command, Incremented, State> commandHandler(ActorRef<String> loggingProbe) {
    return new CommandHandlerBuilder<Command, Incremented, State>(Command.class)
      .matchCommand(Increment.class, (ctx, state, command) -> Effect.persist(new Incremented(1)))
      .matchCommand(GetValue.class, (ctx, state, command) -> {
        command.replyTo.tell(state);
        return Effect.none();
      })
      .matchCommand(IncrementLater.class, (ctx, state, command) -> {
        ActorRef<Object> delay = ctx.spawnAnonymous(Behaviors.withTimers(timers -> {
          timers.startSingleTimer(Tick.instance, Tick.instance, FiniteDuration.create(10, TimeUnit.MILLISECONDS));
          return Behaviors.immutable((context, o) -> Behaviors.stopped());
        }));
        ctx.watchWith(delay, new DelayFinished());
        return Effect.none();
      })
      .matchCommand(DelayFinished.class, (ctx, state, finished) -> {
        return Effect.persist(new Incremented(10));
      })
      .matchCommand(Increment100OnTimeout.class, (ctx, state, msg) -> {
        ctx.setReceiveTimeout(FiniteDuration.create(10, TimeUnit.MILLISECONDS), new Timeout());
        return Effect.none();
      })
      .matchCommand(Timeout.class,
        (ctx, state, msg) -> Effect.persist(timeoutEvent))
      .matchCommand(EmptyEventsListAndThenLog.class, (ctx, state, msg) -> {
        // FIXME we don't get the type inference we want here
        // we could pass in an effects factory that could be typed
        // based on the command handler?
        return Effect.<Incremented, State>persist(Collections.emptyList())
          .andThen(s -> loggingProbe.tell(loggingOne));
      })
      .matchCommand(StopThenLog.class,
        (ctx, state, msg) -> {
          return Effect.<Incremented, State>stop()
            .andThen(s -> loggingProbe.tell(loggingOne));
        })
      .matchCommand(IncrementTwiceAndLog.class,
        (ctx, state, msg) -> Effect.<Incremented, State>persist(
          Arrays.asList(new Incremented(1), new Incremented(1)))
          .andThen(s -> loggingProbe.tell(loggingOne)))
      .build();
  }

  // FIXME factory method
  private static EventHandler<Incremented, State> eventHandler(ActorRef<Pair<State, Incremented>> probe) {
    return new EventHandlerBuilder<Incremented, State>(Incremented.class)
      .matchEvent(Incremented.class, (state, event) -> {
        List<Integer> newHistory = new ArrayList<>(state.history);
        newHistory.add(state.value);
        probe.tell(Pair.create(state, event));
        return new State(state.value + event.delta, newHistory);
      })
      .build();
  }

  private PersistentBehavior<Command, Incremented, State> counter(String persistenceId, ActorRef<Pair<State, Incremented>> probe) {
    ActorRef<String> loggingProbe = new TestProbe<String>(system()).ref();
    return counter(persistenceId, probe, loggingProbe);
  }

  private PersistentBehavior<Command, Incremented, State> counter(String persistenceId) {
    return counter(persistenceId,
      new TestProbe<Pair<State, Incremented>>(system()).ref(),
      new TestProbe<String>(system()).ref());
  }

  private PersistentBehavior<Command, Incremented, State> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    ActorRef<String> loggingProbe
  ) {
    return PersistentBehaviors.immutable(
      persistentId,
      emptyState,
      commandHandler(loggingProbe),
      eventHandler(eventProbe)
    );
  }

  @Test
  public void persistEvents() {
    ActorRef<Command> c = spawn(counter("c2"));
    TestProbe<State> probe = new TestProbe<>(system());
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(1, singletonList(0)));
  }

  @Test
  public void replyStoredEvents() {
    ActorRef<Command> c = spawn(counter("c2"));
    TestProbe<State> probe = new TestProbe<>(system());
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(3, Arrays.asList(0, 1, 2)));

    ActorRef<Command> c2 = spawn(counter("c2"));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(3, Arrays.asList(0, 1, 2)));
    c2.tell(Increment.instance);
    c2.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(4, Arrays.asList(0, 1, 2, 3)));
  }

  @Test
  public void handleTerminatedSignal() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = new TestProbe<>(system());
    ActorRef<Command> c = spawn(counter("c2", eventHandlerProbe.ref()));
    c.tell(Increment.instance);
    c.tell(new IncrementLater());
    eventHandlerProbe.expectMsg(Pair.create(emptyState, new Incremented(1)));
    eventHandlerProbe.expectMsg(Pair.create(new State(1, Collections.singletonList(0)), terminatedEvent));
  }

  @Test
  public void handleReceiveTimeout() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = new TestProbe<>(system());
    ActorRef<Command> c = spawn(counter("c1", eventHandlerProbe.ref()));
    c.tell(new Increment100OnTimeout());
    eventHandlerProbe.expectMsg(Pair.create(emptyState, timeoutEvent));
  }

  @Test
  public void chainableSideEffectsWithEvents() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = new TestProbe<>(system());
    TestProbe<String> loggingProbe = new TestProbe<>(system());
    ActorRef<Command> c = spawn(counter("c1", eventHandlerProbe.ref(), loggingProbe.ref()));
    c.tell(new EmptyEventsListAndThenLog());
    loggingProbe.expectMsg(loggingOne);
  }

  @Test
  public void snapshot() {
    PersistentBehavior<Command, Incremented, State> snapshoter = counter("c11").snapshotOn((s, e, l) -> s.value % 2 == 0);
    ActorRef<Command> c = spawn(snapshoter);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    TestProbe<State> probe = new TestProbe<>(system());
    c.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(3, Arrays.asList(0, 1, 2)));

    TestProbe<Pair<State, Incremented>> eventProbe = new TestProbe<>(system());
    snapshoter = counter("c11", eventProbe.ref()).snapshotOn((s, e, l) -> s.value % 2 == 0);
    ActorRef<Command> c2 = spawn(snapshoter);
    // First 2 are snapshot
    eventProbe.expectMsg(Pair.create(new State(2, Arrays.asList(0, 1)), new Incremented(1)));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMsg(new State(3, Arrays.asList(0, 1, 2)));
  }

  @Test
  public void stopThenLog() {
    TestProbe<State> probe = new TestProbe<>(system());
    ActorRef<Command> c = spawn(counter("c12"));
    c.tell(new StopThenLog());
    probe.expectTerminated(c, FiniteDuration.create(1, TimeUnit.SECONDS));
  }
}
