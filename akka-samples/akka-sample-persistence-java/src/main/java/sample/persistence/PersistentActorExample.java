package sample.persistence;

//#persistent-actor-example
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Procedure;
import akka.persistence.SnapshotOffer;
import akka.persistence.UntypedPersistentActor;

import java.io.Serializable;
import java.util.ArrayList;
import static java.util.Arrays.asList;

class Cmd implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String data;

    public Cmd(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }
}

class Evt implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String data;

    public Evt(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }
}

class ExampleState implements Serializable {
    private static final long serialVersionUID = 1L;
    private final ArrayList<String> events;

    public ExampleState() {
        this(new ArrayList<String>());
    }

    public ExampleState(ArrayList<String> events) {
        this.events = events;
    }

    public ExampleState copy() {
        return new ExampleState(new ArrayList<String>(events));
    }

    public void update(Evt evt) {
        events.add(evt.getData());
    }

    public int size() {
        return events.size();
    }

    @Override
    public String toString() {
        return events.toString();
    }
}

class ExamplePersistentActor extends UntypedPersistentActor {
    @Override
    public String persistenceId() { return "sample-id-1"; }

    private ExampleState state = new ExampleState();

    public int getNumEvents() {
        return state.size();
    }

    @Override
    public void onReceiveRecover(Object msg) {
        if (msg instanceof Evt) {
            state.update((Evt) msg);
        } else if (msg instanceof SnapshotOffer) {
            state = (ExampleState)((SnapshotOffer)msg).snapshot();
        } else {
          unhandled(msg);
        }
    }

    @Override
    public void onReceiveCommand(Object msg) {
        if (msg instanceof Cmd) {
            final String data = ((Cmd)msg).getData();
            final Evt evt1 = new Evt(data + "-" + getNumEvents());
            final Evt evt2 = new Evt(data + "-" + (getNumEvents() + 1));
            persistAll(asList(evt1, evt2), new Procedure<Evt>() {
                public void apply(Evt evt) throws Exception {
                    state.update(evt);
                    if (evt.equals(evt2)) {
                        getContext().system().eventStream().publish(evt);
                    }
                }
            });
        } else if (msg.equals("snap")) {
            // IMPORTANT: create a copy of snapshot
            // because ExampleState is mutable !!!
            saveSnapshot(state.copy());
        } else if (msg.equals("print")) {
            System.out.println(state);
        } else {
          unhandled(msg);
        }
    }
}
//#persistent-actor-example

public class PersistentActorExample {
    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");
        final ActorRef persistentActor = 
            system.actorOf(Props.create(ExamplePersistentActor.class), "persistentActor-4-java");

        persistentActor.tell(new Cmd("foo"), null);
        persistentActor.tell(new Cmd("baz"), null);
        persistentActor.tell(new Cmd("bar"), null);
        persistentActor.tell("snap", null);
        persistentActor.tell(new Cmd("buzz"), null);
        persistentActor.tell("print", null);

        Thread.sleep(10000);
        system.terminate();
    }
}
