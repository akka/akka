/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.Done;
import akka.actor.typed.*;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.Behaviors;
import akka.japi.Pair;
import akka.persistence.SnapshotMetadata;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.NoOffset;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.Sequence;
import akka.persistence.query.journal.leveldb.javadsl.LeveldbReadJournal;
import akka.persistence.typed.EventAdapter;
import akka.persistence.typed.ExpectingReply;
import akka.persistence.typed.PersistenceId;
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

import static akka.Done.done;
import static akka.persistence.typed.scaladsl.EventSourcedBehaviorSpec.*;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class PersistentActorJavaDslTest extends JUnitSuite {

  public static final Config config = conf().withFallback(ConfigFactory.load());

  @ClassRule
  public static final TestKitJunitResource testKit = new TestKitJunitResource(config);


  private LeveldbReadJournal queries = PersistenceQuery.get(Adapter.toUntyped(testKit.system()))
    .getReadJournalFor(LeveldbReadJournal.class, LeveldbReadJournal.Identifier());

  private ActorMaterializer materializer = ActorMaterializer.create(Adapter.toUntyped(testKit.system()));

  interface Command extends Serializable {
  }

  public enum Increment implements Command {
    INSTANCE
  }

  public enum Increment100OnTimeout implements Command {
    INSTANCE
  }

  public enum IncrementLater implements Command {
    INSTANCE
  }

  public enum DelayFinished implements Command {
    INSTANCE
  }

  public enum EmptyEventsListAndThenLog implements Command {
    INSTANCE
  }

  public enum IncrementTwiceAndLog implements Command {
    INSTANCE
  }

  public static class IncrementWithConfirmation implements Command, ExpectingReply<Done> {
    private final ActorRef<Done> replyTo;

    public IncrementWithConfirmation(ActorRef<Done> replyTo) {
      this.replyTo = replyTo;
    }

    @Override
    public ActorRef<Done> replyTo() {
      return replyTo;
    }
  }

  public enum StopThenLog implements Command {
    INSTANCE
  }

  public enum Timeout implements Command {
    INSTANCE
  }

  public static class GetValue implements Command, ExpectingReply<State> {
    private final ActorRef<State> replyTo;

    public GetValue(ActorRef<State> replyTo) {
      this.replyTo = replyTo;
    }

    @Override
    public ActorRef<State> replyTo() {
      return replyTo;
    }
  }

  public static class Incremented implements Serializable {
    final int delta;

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
    final int value;
    final List<Integer> history;

    static final State EMPTY = new State(0, Collections.emptyList());

    public State(int value, List<Integer> history) {
      this.value = value;
      this.history = history;
    }

    State incrementedBy(int delta) {
      List<Integer> newHistory = new ArrayList<>(history);
      newHistory.add(value);
      return new State(value + delta, newHistory);
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

  public enum Tick {
    INSTANCE
  }

  private Behavior<Command> counter(PersistenceId persistenceId) {
    return Behaviors.setup(ctx -> new CounterBehavior(persistenceId, ctx));
  }

  @SuppressWarnings("unused")
  private static class CounterBehavior extends EventSourcedBehavior<Command, Incremented, State> {
    private final ActorContext<Command> ctx;

    CounterBehavior(PersistenceId persistentId, ActorContext<Command> ctx) {
      super(persistentId, SupervisorStrategy.restartWithBackoff(Duration.ofMillis(1), Duration.ofMillis(5), 0.1));
      this.ctx = ctx;
    }

    @Override
    public CommandHandler<Command, Incremented, State> commandHandler() {
      return commandHandlerBuilder(State.class)
          .matchCommand(Increment.class, this::increment)
          .matchCommand(IncrementWithConfirmation.class, this::incrementWithConfirmation)
          .matchCommand(GetValue.class, this::getValue)
          .matchCommand(IncrementLater.class, this::incrementLater)
          .matchCommand(DelayFinished.class, this::delayFinished)
          .matchCommand(Increment100OnTimeout.class, this::increment100OnTimeout)
          .matchCommand(Timeout.class, this::timeout)
          .matchCommand(EmptyEventsListAndThenLog.class, this::emptyEventsListAndThenLog)
          .matchCommand(StopThenLog.class, this::stopThenLog)
          .matchCommand(IncrementTwiceAndLog.class, this::incrementTwiceAndLog)
          .build();

    }

    private Effect<Incremented, State> increment(State state, Increment command) {
      return Effect().persist(new Incremented(1));
    }

    private ReplyEffect<Incremented, State> incrementWithConfirmation(State state, IncrementWithConfirmation command) {
      return Effect()
          .persist(new Incremented(1))
          .thenReply(command, newState -> done());
    }

    private ReplyEffect<Incremented, State> getValue(State state, GetValue command) {
      return Effect().reply(command, state);
    }

    private Effect<Incremented, State> incrementLater(State state, IncrementLater command) {
      ActorRef<Object> delay = ctx.spawnAnonymous(Behaviors.withTimers(timers -> {
        timers.startSingleTimer(Tick.INSTANCE, Tick.INSTANCE, Duration.ofMillis(10));
        return Behaviors.receive((context, o) -> Behaviors.stopped());
      }));
      ctx.watchWith(delay, DelayFinished.INSTANCE);
      return Effect().none();
    }

    private Effect<Incremented, State> delayFinished(State state, DelayFinished command) {
      return Effect().persist(new Incremented(10));
    }

    private Effect<Incremented, State> increment100OnTimeout(State state, Increment100OnTimeout command) {
      ctx.setReceiveTimeout(Duration.ofMillis(10), Timeout.INSTANCE);
      return Effect().none();
    }

    private Effect<Incremented, State> timeout(State state, Timeout command) {
      return Effect().persist(new Incremented(100));
    }

    private Effect<Incremented, State> emptyEventsListAndThenLog(State state, EmptyEventsListAndThenLog command) {
      return Effect()
          .persist(Collections.emptyList())
          .thenRun(s -> log());
    }

    private Effect<Incremented, State> stopThenLog(State state, StopThenLog command) {
      return Effect()
          .stop()
          .thenRun(s -> log());
    }

    private Effect<Incremented, State> incrementTwiceAndLog(State state, IncrementTwiceAndLog command) {
      return Effect()
          .persist(Arrays.asList(new Incremented(1), new Incremented(1)))
          .thenRun(s -> log());
    }

    @Override
    public EventHandler<State, Incremented> eventHandler() {
      return eventHandlerBuilder()
          .matchEvent(Incremented.class, this::applyIncremented)
          .build();
    }

    @Override
    public State emptyState() {
      return State.EMPTY;
    }

    protected State applyIncremented(State state, Incremented event) {
      // override to probe for events
      return state.incrementedBy(event.delta);
    }

    protected void log() {
      // override to probe for logs
    }
  }

  @Test
  public void persistEvents() {
    ActorRef<Command> c = testKit.spawn(counter(new PersistenceId("c1")));
    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.INSTANCE);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }

  @Test
  public void replyStoredEvents() {
    ActorRef<Command> c = testKit.spawn(counter(new PersistenceId("c2")));
    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.INSTANCE);
    c.tell(Increment.INSTANCE);
    c.tell(Increment.INSTANCE);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    ActorRef<Command> c2 = testKit.spawn(counter(new PersistenceId("c2")));
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
    c2.tell(Increment.INSTANCE);
    c2.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(4, Arrays.asList(0, 1, 2, 3)));
  }

  @Test
  public void thenReplyEffect() {
    ActorRef<Command> c = testKit.spawn(counter(new PersistenceId("c1b")));
    TestProbe<Done> probe = testKit.createTestProbe();
    c.tell(new IncrementWithConfirmation(probe.ref()));
    probe.expectMessage(Done.getInstance());
  }

  @Test
  public void handleTerminatedSignal() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    Behavior<Command> counter = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("c3"), ctx) {
          @Override
          protected State applyIncremented(State state, Incremented event) {
            eventHandlerProbe.ref().tell(Pair.create(state, event));
            return super.applyIncremented(state, event);
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(counter);
    c.tell(Increment.INSTANCE);
    c.tell(IncrementLater.INSTANCE);
    eventHandlerProbe.expectMessage(Pair.create(State.EMPTY, new Incremented(1)));
    eventHandlerProbe.expectMessage(Pair.create(new State(1, Collections.singletonList(0)), new Incremented(10)));
  }

  @Test
  public void handleReceiveTimeout() {
    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    Behavior<Command> counter = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("c4"), ctx) {
          @Override
          protected State applyIncremented(State state, Incremented event) {
            eventHandlerProbe.ref().tell(Pair.create(state, event));
            return super.applyIncremented(state, event);
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(counter);
    c.tell(Increment100OnTimeout.INSTANCE);
    eventHandlerProbe.expectMessage(Pair.create(State.EMPTY, new Incremented(100)));
  }

  @Test
  public void chainableSideEffectsWithEvents() {
    TestProbe<String> loggingProbe = testKit.createTestProbe();
    Behavior<Command> counter = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("c5"), ctx) {
          @Override
          protected void log() {
            loggingProbe.ref().tell("logged");
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(counter);
    c.tell(EmptyEventsListAndThenLog.INSTANCE);
    loggingProbe.expectMessage("logged");
  }

  @Test
  public void workWhenWrappedInOtherBehavior() {
    Behavior<Command> behavior = Behaviors.supervise(counter(new PersistenceId("c6"))).onFailure(
      SupervisorStrategy.restartWithBackoff(Duration.ofSeconds(1),
        Duration.ofSeconds(10), 0.1)
    );
    ActorRef<Command> c = testKit.spawn(behavior);

    TestProbe<State> probe = testKit.createTestProbe();
    c.tell(Increment.INSTANCE);
    c.tell(new GetValue(probe.ref()));
    probe.expectMessage(new State(1, singletonList(0)));
  }

  @Test
  public void snapshot() {
    TestProbe<Optional<Throwable>> snapshotProbe = testKit.createTestProbe();

    Behavior<Command> snapshoter = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("snapshot"), ctx) {
          @Override
          public boolean shouldSnapshot(State state, Incremented event, long sequenceNr) {
            return state.value % 2 == 0;
          }
          @Override
          public void onSnapshot(SnapshotMetadata meta, Optional<Throwable> result) {
            snapshotProbe.ref().tell(result);
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(snapshoter);
    c.tell(Increment.INSTANCE);
    c.tell(Increment.INSTANCE);
    snapshotProbe.expectMessage(Optional.empty());
    c.tell(Increment.INSTANCE);

    TestProbe<State> stateProbe = testKit.createTestProbe();
    c.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));

    TestProbe<Pair<State, Incremented>> eventHandlerProbe = testKit.createTestProbe();
    Behavior<Command> recovered = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("snapshot"), ctx) {
          @Override
          protected State applyIncremented(State state, Incremented event) {
            eventHandlerProbe.ref().tell(Pair.create(state, event));
            return super.applyIncremented(state, event);
          }
        }
    );
    ActorRef<Command> c2 = testKit.spawn(recovered);
    // First 2 are snapshot
    eventHandlerProbe.expectMessage(Pair.create(new State(2, Arrays.asList(0, 1)), new Incremented(1)));
    c2.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(3, Arrays.asList(0, 1, 2)));
  }

  @Test
  public void stopThenLog() {
    TestProbe<State> probe = testKit.createTestProbe();
    ActorRef<Command> c = testKit.spawn(counter(new PersistenceId("c12")));
    c.tell(StopThenLog.INSTANCE);
    probe.expectTerminated(c, Duration.ofSeconds(1));
  }

  @Test
  public void tapPersistentActor() {
    TestProbe<Object> interceptProbe = testKit.createTestProbe();
    TestProbe<Signal> signalProbe = testKit.createTestProbe();
    BehaviorInterceptor<Command, Command> tap = new BehaviorInterceptor<Command, Command>() {
      @Override
      public Behavior<Command> aroundReceive(TypedActorContext<Command> ctx, Command msg, ReceiveTarget<Command> target) {
        interceptProbe.ref().tell(msg);
        return target.apply(ctx, msg);
      }

      @Override
      public Behavior<Command> aroundSignal(TypedActorContext<Command> ctx, Signal signal, SignalTarget<Command> target) {
        signalProbe.ref().tell(signal);
        return target.apply(ctx, signal);
      }
    };
    ActorRef<Command> c = testKit.spawn(Behaviors.intercept(tap, counter(new PersistenceId("tap1"))));
    c.tell(Increment.INSTANCE);
    interceptProbe.expectMessage(Increment.INSTANCE);
    signalProbe.expectNoMessage();
  }

  @Test
  public void tagEvent() throws Exception {
    Behavior<Command> tagger = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("tagging"), ctx) {
          @Override
          public Set<String> tagsFor(Incremented incremented) {
            return Sets.newHashSet("tag1", "tag2");
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(tagger);

    c.tell(Increment.INSTANCE);

    TestProbe<State> stateProbe = testKit.createTestProbe();
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
    Behavior<Command> transformer = Behaviors.setup(ctx ->
        new CounterBehavior(new PersistenceId("transform"), ctx) {
          private final EventAdapter<Incremented, ?> adapter = new WrapperEventAdapter();
          public EventAdapter<Incremented, ?> eventAdapter() {
            return adapter;
          }
        }
    );
    ActorRef<Command> c = testKit.spawn(transformer);

    c.tell(Increment.INSTANCE);

    TestProbe<State> stateProbe = testKit.createTestProbe();
    c.tell(new GetValue(stateProbe.ref()));
    stateProbe.expectMessage(new State(1, Collections.singletonList(0)));

    List<EventEnvelope> events = queries.currentEventsByPersistenceId("transform", 0, Long.MAX_VALUE)
      .runWith(Sink.seq(), materializer).toCompletableFuture().get();
    assertEquals(Lists.newArrayList(
      new EventEnvelope(new Sequence(1), "transform", 1, new Wrapper<>(new Incremented(1)))
    ), events);

    ActorRef<Command> c2 = testKit.spawn(transformer);
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

}
