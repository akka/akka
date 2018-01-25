package akka.persistence.typed.javadsl;

import java.util.ArrayList;
import java.util.List;

public class PersistentActorTest {

  interface Command { }

  public static class Incremented {
    private final int delta;

    public Incremented(int delta) {
      this.delta = delta;
    }
  }

  public static class State {
    private final int value;
    private final List<Integer> history;

    public State(int value, List<Integer> history) {
      this.value = value;
      this.history = history;
    }
  }

  static CommandHandler<Command, Incremented, State> commandHandler = null;

  static EventHandler<State, Incremented> eventHandler = null;


  private static final PersistentBehavior<Command, Incremented, State> counter(String persistenceId) {
    return PersistentBehaviors.immutable(
      persistenceId,
      new State(0, new ArrayList<Integer>()),
      commandHandler,
      eventHandler
    );
  }
}
