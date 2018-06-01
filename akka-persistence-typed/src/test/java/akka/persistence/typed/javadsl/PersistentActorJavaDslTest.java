/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.typed.*;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.Behaviors;
import akka.japi.Pair;
import akka.japi.function.Function3;
import akka.japi.function.Function;
import akka.persistence.SnapshotMetadata;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.NoOffset;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.Sequence;
import akka.persistence.query.journal.leveldb.javadsl.LeveldbReadJournal;
import akka.persistence.typed.EventAdapter;
import akka.persistence.typed.NoOpEventAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import java.io.Serializable;
import java.time.Duration;
import java.util.*;

import static akka.persistence.typed.scaladsl.PersistentBehaviorSpec.*;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class PersistentActorJavaDslTest extends JUnitSuite {

  public static final Config config = conf().withFallback(ConfigFactory.load());

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config);


  static final Incremented timeoutEvent = new Incremented(100);
  static final State emptyState = new State(0, Collections.emptyList());
  static final Incremented terminatedEvent = new Incremented(10);

  private LeveldbReadJournal queries = PersistenceQuery.get(Adapter.toUntyped(testKit.system()))
    .getReadJournalFor(LeveldbReadJournal.class, LeveldbReadJournal.Identifier());

  private ActorMaterializer materializer = ActorMaterializer.create(Adapter.toUntyped(testKit.system()));

  interface Command extends Serializable {
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

  public static class Incremented implements Serializable {
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

  public static class State implements Serializable {
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


  private Behavior<Command> counter(String persistenceId, ActorRef<Pair<State, Incremented>> probe) {
    ActorRef<String> loggingProbe = TestProbe.create(String.class, testKit.system()).ref();
    ActorRef<Optional<Throwable>> snapshotProbe = TestProbe.<Optional<Throwable>>create(testKit.system()).ref();
    return counter(persistenceId, probe, loggingProbe, (s, i, l) -> false, (e) -> Collections.emptySet(), snapshotProbe, new NoOpEventAdapter<>());
  }

  private Behavior<Command> counter(String persistenceId,
                                    ActorRef<Pair<State, Incremented>> probe,
                                    Function<Incremented, Set<String>> tagger) {
    ActorRef<String> loggingProbe = TestProbe.create(String.class, testKit.system()).ref();
    ActorRef<Optional<Throwable>> snapshotProbe = TestProbe.<Optional<Throwable>>create(testKit.system()).ref();
    return counter(persistenceId, probe, loggingProbe, (s, i, l) -> false, tagger, snapshotProbe, new NoOpEventAdapter<>());
  }

 private Behavior<Command> counter(String persistenceId,
                                   ActorRef<Pair<State, Incremented>> probe,
                                   EventAdapter<Incremented, ?> transformer) {
    ActorRef<String> loggingProbe = TestProbe.create(String.class, testKit.system()).ref();
    ActorRef<Optional<Throwable>> snapshotProbe = TestProbe.<Optional<Throwable>>create(testKit.system()).ref();
    return counter(persistenceId, probe, loggingProbe, (s, i, l) -> false, e -> Collections.emptySet(), snapshotProbe, transformer);
  }

  private Behavior<Command> counter(String persistenceId) {
    return counter(persistenceId,
      TestProbe.<Pair<State, Incremented>>create(testKit.system()).ref(),
      TestProbe.<String>create(testKit.system()).ref(),
      (s, i, l) -> false,
      (i) -> Collections.emptySet(),
      TestProbe.<Optional<Throwable>>create(testKit.system()).ref(),
      new NoOpEventAdapter<>()
    );
  }

  private Behavior<Command> counter(
    String persistenceId,
    Function3<State, Incremented, Long, Boolean> snapshot,
    ActorRef<Optional<Throwable>> snapshotProbe
  ) {
    return counter(persistenceId,
      testKit.<Pair<State, Incremented>>createTestProbe().ref(),
      testKit.<String>createTestProbe().ref(),
      snapshot,
      e -> Collections.emptySet(),
      snapshotProbe,
      new NoOpEventAdapter<>());
  }

  private Behavior<Command> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    ActorRef<String> loggingProbe) {
    return counter(persistentId, eventProbe, loggingProbe, (s, i, l) -> false, e -> Collections.emptySet(),
      TestProbe.<Optional<Throwable>>create(testKit.system()).ref(),
      new NoOpEventAdapter<>()
    );
  }

  private Behavior<Command> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    Function3<State, Incremented, Long, Boolean> snapshot) {
    return counter(persistentId, eventProbe, testKit.<String>createTestProbe().ref(), snapshot, (e) -> Collections.emptySet(),
      TestProbe.<Optional<Throwable>>create(testKit.system()).ref(), new NoOpEventAdapter<>()
    );
  }

  private static <A> Behavior<Command> counter(
    String persistentId,
    ActorRef<Pair<State, Incremented>> eventProbe,
    ActorRef<String> loggingProbe,
    Function3<State, Incremented, Long, Boolean> snapshot,
    Function<Incremented, Set<String>> tagsFunction,
    ActorRef<Optional<Throwable>> snapshotProbe,
    EventAdapter<Incremented, A> transformer) {

    return Behaviors.setup(ctx -> {
      return new PersistentBehavior<Command, Incremented, State>(persistentId, SupervisorStrategy.restartWithBackoff(Duration.ofMillis(1), Duration.ofMillis(5), 0.1)) {
        @Override
        public CommandHandler<Command, Incremented, State> commandHandler() {
          return commandHandlerBuilder(State.class)
              .matchCommand(Increment.class, (state, command) -> Effect().persist(new Incremented(1)))
              .matchCommand(GetValue.class, (state, command) -> {
                command.replyTo.tell(state);
                return Effect().none();
              })
              .matchCommand(IncrementLater.class, (state, command) -> {
                ActorRef<Object> delay = ctx.spawnAnonymous(Behaviors.withTimers(timers -> {
                  timers.startSingleTimer(Tick.instance, Tick.instance, Duration.ofMillis(10));
                  return Behaviors.receive((context, o) -> Behaviors.stopped());
                }));
                ctx.watchWith(delay, new DelayFinished());
                return Effect().none();
              })
              .matchCommand(DelayFinished.class, (state, finished) -> Effect().persist(new Incremented(10)))
              .matchCommand(Increment100OnTimeout.class, (state, msg) -> {
                ctx.setReceiveTimeout(Duration.ofMillis(10), new Timeout());
                return Effect().none();
              })
              .matchCommand(Timeout.class,
                  (state, msg) -> Effect().persist(timeoutEvent))
              .matchCommand(EmptyEventsListAndThenLog.class, (state, msg) -> Effect().persist(Collections.emptyList())
                  .andThen(s -> loggingProbe.tell(loggingOne)))
              .matchCommand(StopThenLog.class,
                  (state, msg) -> Effect().stop()
                      .andThen(s -> loggingProbe.tell(loggingOne)))
              .matchCommand(IncrementTwiceAndLog.class,
                  (state, msg) -> Effect().persist(
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
        public State emptyState() {
          return emptyState;
        }

        @Override
        public boolean shouldSnapshot(State state, Incremented event, long sequenceNr) {
          try {
            return snapshot.apply(state, event, sequenceNr);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public Set<String> tagsFor(Incremented incremented) {
          try {
            return tagsFunction.apply(incremented);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }

        @Override
        public void onSnapshot(SnapshotMetadata meta, Optional<Throwable> result) {
          snapshotProbe.tell(result);
        }


        @Override
        public EventAdapter<Incremented, A> eventAdapter() {
          return transformer;
        }
      };
    });
  }

  @Test
  public void persistEvents() {
    ActorRef<Command> c = testKit.spawn(counter("c1"));
    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }

  @Test
  public void replyStoredEvents() {
    ActorRef<Command> c = testKit.spawn(counter("c2"));
    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    ActorRef<Command> c2 = testKit.spawn(counter("c2"));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
    c2.tell(Increment.instance);
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(4, Arrays.asList(0, 1, 2, 3)));
  }

  @Test
  public void handleTerminatedSignal() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("c3", eventHandlerProbe.ref()));
    c.tell(Increment.instance);
    c.tell(new IncrementLater());
    eventHandlerProbe.expectMessage(Pair.create(emptyState, new Incremented(1)));
    eventHandlerProbe.expectMessage(Pair.create(new State(1, Collections.singletonList(0)), terminatedEvent));
  }

  @Test
  public void handleReceiveTimeout() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("c4", eventHandlerProbe.ref()));
    c.tell(new Increment100OnTimeout());
    eventHandlerProbe.expectMessage(Pair.create(emptyState, timeoutEvent));
  }

  @Test
  public void chainableSideEffectsWithEvents() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    TestProbe<String> loggingProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("c5", eventHandlerProbe.ref(), loggingProbe.ref()));
    c.tell(new EmptyEventsListAndThenLog());
    loggingProbe.expectMessage(loggingOne);
  }

  @Test
  public void workWhenWrappedInOtherBehavior() {
    Behavior<Command> behavior = Behaviors.supervise(counter("c6")).onFailure(
      SupervisorStrategy.restartWithBackoff(Duration.ofSeconds(1),
        Duration.ofSeconds(10), 0.1)
    );
    ActorRef<Command> c = testKit.spawn(behavior);

    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.instance);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }

  @Test
  public void snapshot() {
    TestProbe<Optional<Throwable>> snapshotProbe = testKit.createTestProbe();
    Behavior<Command> snapshoter = counter("c11", (s, e, l) -> s.value % 2 == 0, snapshotProbe.ref());
    ActorRef<Command> c = testKit.spawn(snapshoter);
    c.tell(Increment.instance);
    c.tell(Increment.instance);
    snapshotProbe.expectMessage(Optional.empty());
    c.tell(Increment.instance);
    TestProbe<State> probe = testKit.createTestProbe();

    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    TestProbe<Pair<State, Incremented>> eventProbe = testKit.createTestProbe();
    snapshoter = counter("c11", eventProbe.ref(), (s, e, l) -> s.value % 2 == 0);
    ActorRef<Command> c2 = testKit.spawn(snapshoter);
    // First 2 are snapshot
    eventProbe.expectMessage(Pair.create(new State(2, Arrays.asList(0, 1)), new Incremented(1)));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
  }

  @Test
  public void stopThenLog() {
    TestProbe<State> probe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("c12"));
    c.tell(new StopThenLog());
    probe.expectTerminated(c, Duration.ofSeconds(1));
  }

  @Test
  public void tapPersistentActor() {
    TestProbe<Command> interceptProbe = testKit.createTestProbe();
    TestProbe<Signal> signalProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(Behaviors.tap(Command.class,
      (ctx, cmd) -> interceptProbe.ref().tell(cmd),
      (ctx, signal) -> signalProbe.ref().tell(signal),
      counter("tap1")));
    c.tell(Increment.instance);
    interceptProbe.expectMessage(Increment.instance);
    signalProbe.expectNoMessage();
  }

  @Test
  public void tagEvent() throws Exception {
    TestProbe<Pair<State, Incremented>> eventProbe = testKit.createTestProbe();
    TestProbe<State> stateProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("tagging", eventProbe.ref(), e -> Sets.newHashSet("tag1", "tag2")));
    c.tell(new Increment());
    c.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(1, Collections.singletonList(0)));

    List<EventEnvelope> events = queries.currentEventsByTag("tag1", NoOffset.getInstance()).runWith(Sink.seq(), materializer)
      .toCompletableFuture().get();
    assertEquals(Lists.newArrayList(
      new EventEnvelope(new Sequence(1), "tagging", 1, new Incremented(1))
    ), events);
  }

  @Test
  public void transformEvent() throws Exception {
    TestProbe<Pair<State, Incremented>> eventProbe = testKit.createTestProbe();
    TestProbe<State> stateProbe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter("transform", eventProbe.ref(), new WrapperEventAdapter()));

    c.tell(new Increment());
    c.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(1, Collections.singletonList(0)));

    List<EventEnvelope> events = queries.currentEventsByPersistenceId("transform", 0, Long.MAX_VALUE)
      .runWith(Sink.seq(), materializer).toCompletableFuture().get();
    assertEquals(Lists.newArrayList(
      new EventEnvelope(new Sequence(1), "transform", 1, new Wrapper<>(new Incremented(1)))
    ), events);

    ActorRef<Command> c2 = testKit.spawn(counter("transform", eventProbe.ref(), new WrapperEventAdapter()));
    c2.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(1, Collections.singletonList(0)));
  }

  //event-wrapper
  class WrapperEventAdapter extends EventAdapter<Incremented, Wrapper> {
    @Override
    public Wrapper toJournal(Incremented incremented) {
      return new Wrapper<>(incremented);
    }

    @Override
    public Incremented fromJournal(Wrapper wrapper) {
      return (Incremented) wrapper.t();
    }
  }
  //event-wrapper

  // FIXME test with by state command handler
}
