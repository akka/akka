/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.AbstractPersistentActor;
import akka.persistence.SnapshotOffer;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.util.ArrayList;

public class SnapshotExample {
  public static class ExampleState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final ArrayList<String> received;

    public ExampleState() {
      this(new ArrayList<String>());
    }

    public ExampleState(ArrayList<String> received) {
      this.received = received;
    }

    public ExampleState copy() {
      return new ExampleState(new ArrayList<String>(received));
    }

    public void update(String s) {
      received.add(s);
    }

    @Override
    public String toString() {
      return received.toString();
    }
  }

  public static class ExamplePersistentActor extends AbstractPersistentActor {
    private ExampleState state = new ExampleState();

    @Override
    public PartialFunction<Object, BoxedUnit> receiveCommand() {
      return ReceiveBuilder.
        match(String.class, s -> s.equals("print"), s -> System.out.println("current state = " + state)).
        match(String.class, s -> s.equals("snap"), s ->
          // IMPORTANT: create a copy of snapshot
          // because ExampleState is mutable !!!
          saveSnapshot(state.copy())).
        match(String.class, s -> {
          persist(s, evt -> {
            state.update(evt);
          });
        }).
        build();
    }

    @Override
    public String persistenceId() { return "sample-id-3"; }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveRecover() {
      return ReceiveBuilder.
        match(String.class, evt -> state.update(evt)).
        match(SnapshotOffer.class, ss -> {
          System.out.println("offered state = " + ss);
          state = (ExampleState) ss.snapshot();
        }).
        build();
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef persistentActor = system.actorOf(Props.create(ExamplePersistentActor.class), "persistentActor-3-java");

    persistentActor.tell("a", null);
    persistentActor.tell("b", null);
    persistentActor.tell("snap", null);
    persistentActor.tell("c", null);
    persistentActor.tell("d", null);
    persistentActor.tell("print", null);

    Thread.sleep(1000);
    system.terminate();
  }
}
