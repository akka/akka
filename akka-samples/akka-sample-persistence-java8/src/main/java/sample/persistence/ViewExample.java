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
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.util.concurrent.TimeUnit;

public class ViewExample {
    public static class ExampleProcessor extends AbstractProcessor {
        @Override
        public String processorId() {
            return "processor-5";
        }

        @Override public PartialFunction<Object, BoxedUnit> receive() {
            return ReceiveBuilder.
                match(Persistent.class,
                      p -> System.out.println(String.format("processor received %s (sequence nr = %d)",
                                                            p.payload(),
                                                            p.sequenceNr()))).build();
        }
    }

    public static class ExampleView extends AbstractView {
        private final ActorRef destination = context().actorOf(Props.create(ExampleDestination.class));
        private final ActorRef channel     = context().actorOf(Channel.props("channel"));

        private int numReplicated = 0;

        @Override
        public String viewId() {
            return "view-5";
        }

        @Override
        public String processorId() {
            return "processor-5";
        }

        @Override public PartialFunction<Object, BoxedUnit> receive() {
            return ReceiveBuilder.
                match(Persistent.class, p -> {
                    numReplicated += 1;
                    System.out.println(String.format("view received %s (sequence nr = %d, num replicated = %d)",
                                                     p.payload(),
                                                     p.sequenceNr(),
                                                     numReplicated));
                    channel.tell(Deliver.create(p.withPayload("replicated-" + p.payload()), destination.path()),
                                 self());
                }).
                match(SnapshotOffer.class, so -> {
                    numReplicated = (Integer) so.snapshot();
                    System.out.println(String.format("view received snapshot offer %s (metadata = %s)",
                                                     numReplicated,
                                                     so.metadata()));
                }).
                match(String.class, s -> s.equals("snap"), s -> saveSnapshot(numReplicated)).build();
        }
    }

    public static class ExampleDestination extends AbstractActor {

        @Override public PartialFunction<Object, BoxedUnit> receive() {
            return ReceiveBuilder.
                match(ConfirmablePersistent.class, cp -> {
                    System.out.println(String.format("destination received %s (sequence nr = %s)",
                                                     cp.payload(),
                                                     cp.sequenceNr()));
                    cp.confirm();
                }).build();
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");
        final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class));
        final ActorRef view = system.actorOf(Props.create(ExampleView.class));

        system.scheduler()
            .schedule(Duration.Zero(),
                      Duration.create(2, TimeUnit.SECONDS),
                      processor,
                      Persistent.create("scheduled"),
                      system.dispatcher(),
                      null);
        system.scheduler()
            .schedule(Duration.Zero(), Duration.create(5, TimeUnit.SECONDS), view, "snap", system.dispatcher(), null);
    }
}
