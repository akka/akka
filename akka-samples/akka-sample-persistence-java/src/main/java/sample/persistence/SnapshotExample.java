package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Procedure;
import akka.persistence.SaveSnapshotFailure;
import akka.persistence.SaveSnapshotSuccess;
import akka.persistence.SnapshotOffer;
import akka.persistence.UntypedPersistentActor;

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

    public static class ExamplePersistentActor extends UntypedPersistentActor {
        @Override
        public String persistenceId() { return "sample-id-3"; }

        private ExampleState state = new ExampleState();

        @Override
        public void onReceiveCommand(Object message) {
            if (message.equals("print")) {
                System.out.println("current state = " + state);
            } else if (message.equals("snap")) {
                // IMPORTANT: create a copy of snapshot
                // because ExampleState is mutable !!!
                saveSnapshot(state.copy());
            } else if (message instanceof SaveSnapshotSuccess) {
              // ...
            } else if (message instanceof SaveSnapshotFailure) {
              // ...  
            } else if (message instanceof String) {
              String s = (String) message;
              persist(s, new Procedure<String>() {
                public void apply(String evt) throws Exception {
                  state.update(evt);
                }
              });
            } else {
              unhandled(message);
            }
        }
        
        @Override
        public void onReceiveRecover(Object message) {
          if (message instanceof SnapshotOffer) {
            ExampleState s = (ExampleState)((SnapshotOffer)message).snapshot();
            System.out.println("offered state = " + s);
            state = s;
          } else if (message instanceof String) {
            state.update((String) message);
          } else {
            unhandled(message);
          }
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

        Thread.sleep(10000);
        system.terminate();
    }
}
