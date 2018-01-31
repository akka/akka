package akka.persistence.typed.javadsl;

import akka.actor.typed.javadsl.ActorContext;
import akka.japi.function.Function3;

import java.util.ArrayList;
import java.util.List;

public class PersistentActorCompileOnlyTest {

  //#command
  public static class SimpleCommand {
    private final String data;

    public SimpleCommand(String data) {
      this.data = data;
    }
  }
  //#command

  //#event
  public static class SimpleEvent {
    private final String data;

    public SimpleEvent(String data) {
      this.data = data;
    }
  }
  //#event

  //#state
  public static class SimpleState {
    private final List<String> events = new ArrayList<>();

    public SimpleState addEvent(SimpleEvent event) {
      events.add(event.data);
      return this;
    }
  }
  //#state

  //#command-handler
  private static CommandHandler<SimpleCommand, SimpleEvent, SimpleState> commandHandler =
    (ctx, state, cmd) -> Effect.persist(new SimpleEvent(cmd.data));
  //#command-handler

  //#event-handler
  public static EventHandler<SimpleEvent, SimpleState> eventHandler =
    (state, event) -> state.addEvent(event);
  //#event-handler

  //#behavior
  public static PersistentBehavior<SimpleCommand, SimpleEvent, SimpleState> pb =
    PersistentBehaviors.immutable(
      "p1",
      new SimpleState(),
      commandHandler,
      eventHandler
    );

  //#behavior
}
