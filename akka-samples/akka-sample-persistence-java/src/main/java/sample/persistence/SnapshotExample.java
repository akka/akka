package sample.persistence;

import java.io.Serializable;
import java.util.ArrayList;

import akka.actor.*;
import akka.persistence.*;

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

    public static class ExampleProcessor extends UntypedProcessor {
        private ExampleState state = new ExampleState();

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent persistent = (Persistent)message;
                state.update(String.format("%s-%d", persistent.payload(), persistent.sequenceNr()));
            } else if (message instanceof SnapshotOffer) {
                ExampleState s = (ExampleState)((SnapshotOffer)message).snapshot();
                System.out.println("offered state = " + s);
                state = s;
            } else if (message.equals("print")) {
                System.out.println("current state = " + state);
            } else if (message.equals("snap")) {
                // IMPORTANT: create a copy of snapshot
                // because ExampleState is mutable !!!
                saveSnapshot(state.copy());
            }
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
