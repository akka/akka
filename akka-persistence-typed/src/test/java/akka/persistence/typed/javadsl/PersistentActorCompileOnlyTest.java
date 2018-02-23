/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl;

import akka.actor.Scheduler;
import akka.actor.typed.ActorRef;
import akka.testkit.typed.javadsl.TestInbox;
import akka.util.Timeout;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static akka.actor.typed.javadsl.AskPattern.ask;

public class PersistentActorCompileOnlyTest {

  public static abstract class Simple {
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
    public static PersistentBehavior<SimpleCommand, SimpleEvent, SimpleState> pb = new PersistentBehavior<SimpleCommand, SimpleEvent, SimpleState>("p1") {
      @Override
      public SimpleState initialState() {
        return new SimpleState();
      }

      //#command-handler
      @Override
      public CommandHandler<SimpleCommand, SimpleEvent, SimpleState> commandHandler() {
        return (ctx, state, cmd) -> Effect().persist(new SimpleEvent(cmd.data));
      }
      //#command-handler

      //#event-handler
      @Override
      public EventHandler<SimpleEvent, SimpleState> eventHandler() {
        return (state, event) -> state.addEvent(event);
      }
      //#event-handler
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

    private PersistentBehavior<MyCommand, MyEvent, ExampleState> pa = new PersistentBehavior<MyCommand, MyEvent, ExampleState>("pa") {
      @Override
      public ExampleState initialState() {
        return new ExampleState();
      }

      @Override
      public CommandHandler<MyCommand, MyEvent, ExampleState> commandHandler() {
        return commandHandlerBuilder()
          .matchCommand(Cmd.class, (ctx, state, cmd) -> Effect().persist(new Evt(cmd.data))
            .andThen(() -> cmd.sender.tell(new Ack())))
          .build();
      }

      @Override
      public EventHandler<MyEvent, ExampleState> eventHandler() {
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
    static Timeout timeout = new Timeout(1, TimeUnit.SECONDS);

    private static void performSideEffect(ActorRef<AcknowledgeSideEffect> sender, int correlationId, String data, Scheduler scheduler) {
      CompletionStage<Response> what = ask(sideEffectProcessor, (ActorRef<Response> ar) -> new Request(correlationId, data, ar), timeout, scheduler);
      what.thenApply(r -> new AcknowledgeSideEffect(r.correlationId))
        .thenAccept(sender::tell);
    }

    class MyPersistentBehavior extends PersistentBehavior<Command, Event, RecoveryComplete.EventsInFlight> {
      public MyPersistentBehavior(String persistenceId) {
        super(persistenceId);
      }

      @Override
      public EventsInFlight initialState() {
        return new EventsInFlight(0, Collections.emptyMap());
      }

      @Override
      public CommandHandler<Command, Event, EventsInFlight> commandHandler() {
        return commandHandlerBuilder()
          .matchCommand(DoSideEffect.class,
            (ctx, state, cmd) -> Effect().persist(new IntentRecord(state.nextCorrelationId, cmd.data))
              .andThen(() -> performSideEffect(ctx.getSelf().narrow(), state.nextCorrelationId, cmd.data, ctx.getSystem().scheduler())))
          .matchCommand(AcknowledgeSideEffect.class, (ctx, state, command) -> Effect().persist(new SideEffectAcknowledged(command.correlationId)))
          .build();
      }

      @Override
      public EventHandler<Event, EventsInFlight> eventHandler() {
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
