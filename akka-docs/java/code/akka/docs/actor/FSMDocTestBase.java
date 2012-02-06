/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor;

//#imports-data
import java.util.ArrayList;
import java.util.List;
import akka.actor.ActorRef;
//#imports-data

//#imports-actor
import akka.event.LoggingAdapter;
import akka.event.Logging;
import akka.actor.UntypedActor;
//#imports-actor

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.TestProbe;
import akka.testkit.AkkaSpec;

public class FSMDocTestBase {

  //#data
  public static final class SetTarget {
    final ActorRef ref;

    public SetTarget(ActorRef ref) {
      this.ref = ref;
    }
  }

  public static final class Queue {
    final Object o;

    public Queue(Object o) {
      this.o = o;
    }
  }

  public static final Object flush = new Object();

  public static final class Batch {
    final List<Object> objects;

    public Batch(List<Object> objects) {
      this.objects = objects;
    }
  }

  //#data

  //#base
  static abstract class MyFSMBase extends UntypedActor {

    /*
     * This is the mutable state of this state machine.
     */
    protected enum State {
      IDLE, ACTIVE;
    }

    private State state = State.IDLE;
    private ActorRef target;
    private List<Object> queue;

    /*
     * Then come all the mutator methods:
     */
    protected void init(ActorRef target) {
      this.target = target;
      queue = new ArrayList<Object>();
    }

    protected void setState(State s) {
      if (state != s) {
        transition(state, s);
        state = s;
      }
    }

    protected void enqueue(Object o) {
      if (queue != null)
        queue.add(o);
    }

    protected List<Object> drainQueue() {
      final List<Object> q = queue;
      if (q == null)
        throw new IllegalStateException("drainQueue(): not yet initialized");
      queue = new ArrayList<Object>();
      return q;
    }

    /*
     * Here are the interrogation methods:
     */
    protected boolean isInitialized() {
      return target != null;
    }

    protected State getState() {
      return state;
    }

    protected ActorRef getTarget() {
      if (target == null)
        throw new IllegalStateException("getTarget(): not yet initialized");
      return target;
    }

    /*
     * And finally the callbacks (only one in this example: react to state change)
     */
    abstract protected void transition(State old, State next);
  }

  //#base

  //#actor
  static public class MyFSM extends MyFSMBase {

    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void onReceive(Object o) {

      if (getState() == State.IDLE) {

        if (o instanceof SetTarget)
          init(((SetTarget) o).ref);

        else
          whenUnhandled(o);

      } else if (getState() == State.ACTIVE) {

        if (o == flush)
          setState(State.IDLE);

        else
          whenUnhandled(o);
      }
    }

    @Override
    public void transition(State old, State next) {
      if (old == State.ACTIVE) {
        getTarget().tell(new Batch(drainQueue()));
      }
    }

    private void whenUnhandled(Object o) {
      if (o instanceof Queue && isInitialized()) {
        enqueue(((Queue) o).o);
        setState(State.ACTIVE);

      } else {
        log.warning("received unknown message {} in state {}", o, getState());
      }
    }
  }

  //#actor

  ActorSystem system;

  @org.junit.Before
  public void setUp() {
    system = ActorSystem.create("FSMSystem", AkkaSpec.testConf());
  }

  @org.junit.Test
  public void mustBunch() {
    final ActorRef buncher = system.actorOf(new Props(MyFSM.class));
    final TestProbe probe = new TestProbe(system);
    buncher.tell(new SetTarget(probe.ref()));
    buncher.tell(new Queue(1));
    buncher.tell(new Queue(2));
    buncher.tell(flush);
    buncher.tell(new Queue(3));
    final Batch b = probe.expectMsgClass(Batch.class);
    assert b.objects.size() == 2;
    assert b.objects.contains(1);
    assert b.objects.contains(2);
  }

  @org.junit.After
  public void cleanup() {
    system.shutdown();
  }

}
