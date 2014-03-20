/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.*;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

public class ProcessorChannelExample {
  public static class ExampleProcessor extends AbstractProcessor {
    private ActorRef destination;
    private ActorRef channel;

    public ExampleProcessor(ActorRef destination) {
      this.destination = destination;
      this.channel = context().actorOf(Channel.props(), "channel");

      receive(ReceiveBuilder.
        match(Persistent.class, p -> {
          System.out.println("processed " + p.payload());
          channel.tell(Deliver.create(p.withPayload("processed " + p.payload()), destination.path()), self());
        }).
        match(String.class, s -> System.out.println("reply = " + s)).build()
      );
    }
  }

  public static class ExampleDestination extends AbstractActor {
    public ExampleDestination() {
      receive(ReceiveBuilder.
        match(ConfirmablePersistent.class, cp -> {
          System.out.println("received " + cp.payload());
          sender().tell(String.format("re: %s (%d)", cp.payload(), cp.sequenceNr()), null);
          cp.confirm();
        }).build()
      );
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef destination = system.actorOf(Props.create(ExampleDestination.class));
    final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class, destination), "processor-1");

    processor.tell(Persistent.create("a"), null);
    processor.tell(Persistent.create("b"), null);

    Thread.sleep(1000);
    system.shutdown();
  }
}
