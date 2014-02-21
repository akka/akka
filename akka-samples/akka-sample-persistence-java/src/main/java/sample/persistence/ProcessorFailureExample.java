package sample.persistence;

import java.util.ArrayList;

import scala.Option;

import akka.actor.*;
import akka.persistence.*;

public class ProcessorFailureExample {
    public static class ExampleProcessor extends UntypedProcessor {
        private ArrayList<Object> received = new ArrayList<Object>();

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent persistent = (Persistent)message;
                if (persistent.payload() == "boom") {
                    throw new Exception("boom");
                } else {
                    received.add(persistent.payload());
                }
            } else if (message == "boom") {
                throw new Exception("boom");
            } else if (message == "print") {
                System.out.println("received " + received);
            }
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
