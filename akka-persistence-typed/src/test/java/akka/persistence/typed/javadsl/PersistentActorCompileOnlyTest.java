/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.Scheduler;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.EventAdapter;
import akka.actor.testkit.typed.javadsl.TestInbox;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.SideEffect;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;

import static akka.actor.typed.javadsl.AskPattern.ask;

public class PersistentActorCompileOnlyTest {


  public static abstract class Simple {

    //#event-wrapper
    public static class Wrapper<T> {
      private final T t;
      public Wrapper(T t) {
        this.t = t;
      }
      public T getT() {
        return t;
      }
    }

    public static class EventAdapterExample extends EventAdapter<SimpleEvent, Wrapper<SimpleEvent>> {
      @Override
      public Wrapper<SimpleEvent> toJournal(SimpleEvent simpleEvent) {
        return new Wrapper<>(simpleEvent);
      }
      @Override
      public SimpleEvent fromJournal(Wrapper<SimpleEvent> simpleEventWrapper) {
        return simpleEventWrapper.getT();
      }
    }
    //#event-wrapper

    //#command
    public static class SimpleCommand {
      public final String data;

      public SimpleCommand(String data) {
        this.data = data;
      }
    }
    //#command

    //#event
    static class SimpleEvent {
      private final String data;

      SimpleEvent(String data) {
        this.data = data;
      }
    }
    //#event

    //#state
    static class SimpleState {
      private final List<String> events;

      SimpleState(List<String> events) {
        this.events = events;
      }

      SimpleState() {
        this.events = new ArrayList<>();
      }


      SimpleState addEvent(SimpleEvent event) {
        List<String> newEvents = new ArrayList<>(events);
        newEvents.add(event.data);
        return new SimpleState(newEvents);
      }
    }
    //#state


    //#behavior
    public static EventSourcedBehavior<SimpleCommand, SimpleEvent, SimpleState> pb =
        new EventSourcedBehavior<SimpleCommand, SimpleEvent, SimpleState>(new PersistenceId("p1")) {

      @Override
      public SimpleState emptyState() {
        return new SimpleState();
      }

      //#command-handler
      @Override
      public CommandHandler<SimpleCommand, SimpleEvent, SimpleState> commandHandler() {
        return (state, cmd) -> Effect().persist(new SimpleEvent(cmd.data));
      }
      //#command-handler

      //#event-handler
      @Override
      public EventHandler<SimpleState, SimpleEvent> eventHandler() {
        return (state, event) -> state.addEvent(event);
      }
      //#event-handler

      //#install-event-adapter
      @Override
      public EventAdapter<SimpleEvent, Wrapper<SimpleEvent>> eventAdapter() {
        return new EventAdapterExample();
      }
      //#install-event-adapter
    };

    //#behavior
  }

  static abstract class WithAck {
    public static class Ack {
    }

    interface MyCommand {
    }
    public static class Cmd implements MyCommand {
      private final String data;
      private final ActorRef<Ack> sender;

      public Cmd(String data, ActorRef<Ack> sender) {
        this.data = data;
        this.sender = sender;
      }
    }

    interface MyEvent {
    }
    public static class Evt implements MyEvent {
      private final String data;

      public Evt(String data) {
        this.data = data;
      }
    }

    static class ExampleState {
      private List<String> events = new ArrayList<>();
    }

    //#commonChainedEffects
    // Factored out Chained effect
    static final SideEffect<ExampleState>  commonChainedEffect =
        SideEffect.create(s -> System.out.println("Command handled!"));

    //#commonChainedEffects

    private EventSourcedBehavior<MyCommand, MyEvent, ExampleState> pa =
        new EventSourcedBehavior<MyCommand, MyEvent, ExampleState>(new PersistenceId("pa")) {

      @Override
      public ExampleState emptyState() {
        return new ExampleState();
      }

      @Override
      public CommandHandler<MyCommand, MyEvent, ExampleState> commandHandler() {

     //#commonChainedEffects
     return commandHandlerBuilder(ExampleState.class)
       .matchCommand(Cmd.class, (state, cmd) -> Effect().persist(new Evt(cmd.data))
         .thenRun(() -> cmd.sender.tell(new Ack()))
         .andThen(commonChainedEffect)
       )
       .build();
     //#commonChainedEffects
      }

      @Override
      public EventHandler<ExampleState, MyEvent> eventHandler() {
        return eventHandlerBuilder()
          .matchEvent(Evt.class, (state, event) -> {
            state.events.add(event.data);
            return state;
          })
          .build();
      }
    };
  }

  static abstract class RecoveryComplete {
    interface Command {
    }
    static class DoSideEffect implements Command {
      final String data;

      DoSideEffect(String data) {
        this.data = data;
      }
    }

    static class AcknowledgeSideEffect implements Command {
      final int correlationId;

      AcknowledgeSideEffect(int correlationId) {
        this.correlationId = correlationId;
      }
    }

    interface Event {
    }

    static class IntentRecord implements Event {
      final int correlationId;
      final String data;

      IntentRecord(int correlationId, String data) {
        this.correlationId = correlationId;
        this.data = data;
      }
    }

    static class SideEffectAcknowledged implements Event {
      final int correlationId;

      SideEffectAcknowledged(int correlationId) {
        this.correlationId = correlationId;
      }
    }

    static class EventsInFlight {
      final int nextCorrelationId;
      final Map<Integer, String> dataByCorrelationId;

      EventsInFlight(int nextCorrelationId, Map<Integer, String> dataByCorrelationId) {
        this.nextCorrelationId = nextCorrelationId;
        this.dataByCorrelationId = dataByCorrelationId;
      }
    }

    static class Request {
      final int correlationId;
      final String data;
      final ActorRef<Response> sender;

      Request(int correlationId, String data, ActorRef<Response> sender) {
        this.correlationId = correlationId;
        this.data = data;
        this.sender = sender;
      }
    }

    static class Response {
      final int correlationId;

      Response(int correlationId) {
        this.correlationId = correlationId;
      }
    }

    static ActorRef<Request> sideEffectProcessor = TestInbox.<Request>create().getRef();
    static Duration timeout = Duration.ofSeconds(1);

    private static void performSideEffect(ActorRef<AcknowledgeSideEffect> sender, int correlationId, String data, Scheduler scheduler) {
      CompletionStage<Response> what = ask(sideEffectProcessor, (ActorRef<Response> ar) -> new Request(correlationId, data, ar), timeout, scheduler);
      what.thenApply(r -> new AcknowledgeSideEffect(r.correlationId))
        .thenAccept(sender::tell);
    }

    // #actor-context
    public Behavior<Command> behavior(PersistenceId persistenceId) {
      return Behaviors.setup(ctx -> new MyPersistentBehavior(persistenceId, ctx));
    }

    // #actor-context

    // #actor-context
    class MyPersistentBehavior extends EventSourcedBehavior<Command, Event, RecoveryComplete.EventsInFlight> {

      // this makes the context available to the command handler etc.
      private final ActorContext<Command> ctx;

      public MyPersistentBehavior(PersistenceId persistenceId, ActorContext<Command> ctx) {
        super(persistenceId);
        this.ctx = ctx;
      }
      // #actor-context

      @Override
      public EventsInFlight emptyState() {
        return new EventsInFlight(0, Collections.emptyMap());
      }

      @Override
      public CommandHandler<Command, Event, EventsInFlight> commandHandler() {
        return commandHandlerBuilder(EventsInFlight.class)
          .matchCommand(DoSideEffect.class,
            (state, cmd) -> Effect().persist(new IntentRecord(state.nextCorrelationId, cmd.data))
              .thenRun(() -> performSideEffect(ctx.getSelf().narrow(), state.nextCorrelationId, cmd.data, ctx.getSystem().scheduler())))
          .matchCommand(AcknowledgeSideEffect.class, (state, command) -> Effect().persist(new SideEffectAcknowledged(command.correlationId)))
          .build();
      }

      @Override
      public EventHandler<EventsInFlight, Event> eventHandler() {
        return eventHandlerBuilder()
          .matchEvent(IntentRecord.class, (state, event) -> {
            int nextCorrelationId = event.correlationId;
            Map<Integer, String> newOutstanding = new HashMap<>(state.dataByCorrelationId);
            newOutstanding.put(event.correlationId, event.data);
            return new EventsInFlight(nextCorrelationId, newOutstanding);
          })
          .matchEvent(SideEffectAcknowledged.class, (state, event) -> {
            Map<Integer, String> newOutstanding = new HashMap<>(state.dataByCorrelationId);
            newOutstanding.remove(event.correlationId);
            return new EventsInFlight(state.nextCorrelationId, newOutstanding);
          })
          .build();
      }
    }
  }

}
