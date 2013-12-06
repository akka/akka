/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence.japi;

import akka.actor.*;
import akka.persistence.*;

public class ProcessorChannelExample {
    public static class ExampleProcessor extends UntypedProcessor {
        private ActorRef destination;
        private ActorRef channel;

        public ExampleProcessor(ActorRef destination) {
            this.destination = destination;
            this.channel = getContext().actorOf(Channel.props(), "channel");
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent msg = (Persistent)message;
                System.out.println("processed " + msg.payload());
                channel.tell(Deliver.create(msg.withPayload("processed " + msg.payload()), destination), getSelf());
            }
        }
    }

    public static class ExampleDestination extends UntypedActor {
        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof ConfirmablePersistent) {
                ConfirmablePersistent msg = (ConfirmablePersistent)message;
                msg.confirm();
                System.out.println("received " + msg.payload());
            }
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
