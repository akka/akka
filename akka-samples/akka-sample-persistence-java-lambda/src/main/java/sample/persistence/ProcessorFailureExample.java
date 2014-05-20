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
import scala.Option;

import java.util.ArrayList;

public class ProcessorFailureExample {
  public static class ExampleProcessor extends AbstractProcessor {
    private ArrayList<Object> received = new ArrayList<Object>();

    public ExampleProcessor() {
      receive(ReceiveBuilder.
        match(Persistent.class, p -> p.payload().equals("boom"), p -> {throw new RuntimeException("boom");}).
        match(Persistent.class, p -> !p.payload().equals("boom"), p -> received.add(p.payload())).
        match(String.class, s -> s.equals("boom"), s -> {throw new RuntimeException("boom");}).
        match(String.class, s -> s.equals("print"), s -> System.out.println("received " + received)).build()
      );
    }

    @Override
    public void preRestart(Throwable reason, Option<Object> message) {
      if (message.isDefined() && message.get() instanceof Persistent) {
        deleteMessage(((Persistent) message.get()).sequenceNr(), false);
      }
      super.preRestart(reason, message);
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class), "processor-2");

    processor.tell(Persistent.create("a"), null);
    processor.tell("print", null);
    processor.tell("boom", null);
    processor.tell("print", null);
    processor.tell(Persistent.create("b"), null);
    processor.tell("print", null);
    processor.tell(Persistent.create("boom"), null);
    processor.tell("print", null);
    processor.tell(Persistent.create("c"), null);
    processor.tell("print", null);

    // Will print in a first run (i.e. with empty journal):

    // received [a]
    // received [a, b]
    // received [a, b, c]

    // Will print in a second run:

    // received [a, b, c, a]
    // received [a, b, c, a, b]
    // received [a, b, c, a, b, c]

    // etc ...

    Thread.sleep(1000);
    system.shutdown();
  }
}
