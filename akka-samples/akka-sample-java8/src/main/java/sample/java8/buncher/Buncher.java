package sample.java8.buncher;

//#simple-imports
import akka.actor.AbstractFSM;
import akka.actor.ActorRef;
import akka.japi.pf.UnitMatch;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import scala.concurrent.duration.Duration;
//#simple-imports

import static sample.java8.buncher.Buncher.State;
import static sample.java8.buncher.Buncher.Data;
import static sample.java8.buncher.Buncher.State.*;
import static sample.java8.buncher.Buncher.Uninitialized.*;
import static sample.java8.buncher.Events.*;

//#simple-fsm
public class Buncher extends AbstractFSM<State, Data> {

  {
    //#fsm-body
    startWith(Idle, Uninitialized);

    //#when-syntax
    when(Idle,
      matchEvent(SetTarget.class, Uninitialized.class,
        (setTarget, uninitialized) -> stay().using(new Todo(setTarget.getRef(), new LinkedList<>()))));
    //#when-syntax

    //#transition-elided
    onTransition(
      matchState(Active, Idle, () -> {
        // reuse this matcher
        final UnitMatch<Data> m = UnitMatch.create(
          matchData(Todo.class,
            todo -> todo.getTarget().tell(new Batch(todo.getQueue()), self())));
        m.match(stateData());
      }).
      state(Idle, Active, () -> {/* Do something here */}));
    //#transition-elided

    when(Active, Duration.create(1, "second"),
      matchEvent(Arrays.asList(Flush.class, StateTimeout()), Todo.class,
        todo -> goTo(Idle).using(todo.copy(new LinkedList<>()))));

    //#unhandled-elided
    whenUnhandled(
      matchEvent(Queue.class, Todo.class,
        (queue, todo) -> goTo(Active).using(todo.addElement(queue.getObj()))).
        anyEvent((event, state) -> {
          log().warning("received unhandled request {} in state {}/{}", event, stateName(), state);
          return stay();
        }));
    //#unhandled-elided

    //#termination-elided
    //#termination-syntax
    onTermination(
      matchStop(Normal(),
        (state, data) -> {/* Do something here */}).
      stop(Shutdown(),
        (state, data) -> {/* Do something here */}).
      stop(Failure(),
        (reason, state, data) -> {/* Do something here */}));
    //#termination-syntax
    //#termination-elided
    //#fsm-body

    initialize();
  }
  //#simple-fsm

  static
  //#simple-state
  // states
  enum State {
    Idle, Active
  }

  //#simple-state
  static
  //#simple-state
  // state data
  interface Data {
  }

  //#simple-state
  static
  //#simple-state
  enum Uninitialized implements Data {
    Uninitialized
  }

  //#simple-state
  static
  //#simple-state
  final class Todo implements Data {
    private final ActorRef target;
    private final List<Object> queue;

    public Todo(ActorRef target, List<Object> queue) {
      this.target = target;
      this.queue = queue;
    }

    public ActorRef getTarget() {
      return target;
    }

    public List<Object> getQueue() {
      return queue;
    }
    //#boilerplate

    @Override
    public String toString() {
      return "Todo{" +
        "target=" + target +
        ", queue=" + queue +
        '}';
    }

    public Todo addElement(Object element) {
      List<Object> nQueue = new LinkedList<>(queue);
      nQueue.add(element);
      return new Todo(this.target, nQueue);
    }

    public Todo copy(List<Object> queue) {
      return new Todo(this.target, queue);
    }

    public Todo copy(ActorRef target) {
      return new Todo(target, this.queue);
    }
    //#boilerplate
  }
  //#simple-state
  //#simple-fsm
}
//#simple-fsm

//#NullFunction
//#unhandled-syntax
//#modifier-syntax
//#transition-syntax
//#stop-syntax
// TODO add code example here
//#stop-syntax
//#transition-syntax
//#modifier-syntax
//#unhandled-syntax
//#NullFunction
