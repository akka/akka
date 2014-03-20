/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.AbstractProcessor;
import akka.persistence.Persistent;
import akka.persistence.SnapshotOffer;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.util.ArrayList;

public class SnapshotExample {
  public static class ExampleState implements Serializable {
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

  public static class ExampleProcessor extends AbstractProcessor {
    private ExampleState state = new ExampleState();

    public ExampleProcessor() {
      receive(ReceiveBuilder.
        match(Persistent.class, p -> state.update(String.format("%s-%d", p.payload(), p.sequenceNr()))).
        match(SnapshotOffer.class, s -> {
          ExampleState exState = (ExampleState) s.snapshot();
          System.out.println("offered state = " + exState);
          state = exState;
        }).
        match(String.class, s -> s.equals("print"), s -> System.out.println("current state = " + state)).
        match(String.class, s -> s.equals("snap"), s ->
          // IMPORTANT: create a copy of snapshot
          // because ExampleState is mutable !!!
          saveSnapshot(state.copy())).build()
      );
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class), "processor-3-java");

    processor.tell(Persistent.create("a"), null);
    processor.tell(Persistent.create("b"), null);
    processor.tell("snap", null);
    processor.tell(Persistent.create("c"), null);
    processor.tell(Persistent.create("d"), null);
    processor.tell("print", null);

    Thread.sleep(1000);
    system.shutdown();
  }
}
