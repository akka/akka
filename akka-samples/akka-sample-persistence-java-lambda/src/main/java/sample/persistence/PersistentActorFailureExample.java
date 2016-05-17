/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.AbstractPersistentActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import java.util.ArrayList;

public class PersistentActorFailureExample {
  public static class ExamplePersistentActor extends AbstractPersistentActor {
    private ArrayList<Object> received = new ArrayList<Object>();

    @Override
    public String persistenceId() { return "sample-id-2"; }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveCommand() {
      return ReceiveBuilder.
        match(String.class, s -> s.equals("boom"), s -> {throw new RuntimeException("boom");}).
        match(String.class, s -> s.equals("print"), s -> System.out.println("received " + received)).
        match(String.class, s -> {
          persist(s, evt -> {
            received.add(evt);
          });
        }).
        build();
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receiveRecover() {
      return ReceiveBuilder.
        match(String.class, s -> received.add(s)).
        build();
    }


  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef persistentActor = system.actorOf(Props.create(ExamplePersistentActor.class), "persistentActor-2");

    persistentActor.tell("a", null);
    persistentActor.tell("print", null);
    persistentActor.tell("boom", null);
    persistentActor.tell("print", null);
    persistentActor.tell("b", null);
    persistentActor.tell("print", null);
    persistentActor.tell("c", null);
    persistentActor.tell("print", null);

    // Will print in a first run (i.e. with empty journal):

    // received [a]
    // received [a, b]
    // received [a, b, c]

    // Will print in a second run:

    // received [a, b, c, a]
    // received [a, b, c, a, b]
    // received [a, b, c, a, b, c]

    // etc ...

    Thread.sleep(10000);
    system.terminate();
  }
}
