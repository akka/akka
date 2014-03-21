package sample.persistence;

//#eventsourced-example
import java.io.Serializable;
import java.util.ArrayList;

import akka.actor.*;
import akka.japi.Procedure;
import akka.persistence.*;

import static java.util.Arrays.asList;

class Cmd implements Serializable {
    private final String data;

    public Cmd(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }
}

class Evt implements Serializable {
    private final String data;

    public Evt(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }
}

class ExampleState implements Serializable {
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

class ExampleProcessor extends UntypedEventsourcedProcessor {
    private ExampleState state = new ExampleState();

    public int getNumEvents() {
        return state.size();
    }

    public void onReceiveRecover(Object msg) {
        if (msg instanceof Evt) {
            state.update((Evt) msg);
        } else if (msg instanceof SnapshotOffer) {
            state = (ExampleState)((SnapshotOffer)msg).snapshot();
        }
    }

    public void onReceiveCommand(Object msg) {
        if (msg instanceof Cmd) {
            final String data = ((Cmd)msg).getData();
            final Evt evt1 = new Evt(data + "-" + getNumEvents());
            final Evt evt2 = new Evt(data + "-" + (getNumEvents() + 1));
            persist(asList(evt1, evt2), new Procedure<Evt>() {
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
        }
    }
}
//#eventsourced-example

public class EventsourcedExample {
    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");
        final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class), "processor-4-java");

        processor.tell(new Cmd("foo"), null);
        processor.tell(new Cmd("baz"), null);
        processor.tell(new Cmd("bar"), null);
        processor.tell("snap", null);
        processor.tell(new Cmd("buzz"), null);
        processor.tell("print", null);

        Thread.sleep(1000);
        system.shutdown();
    }
}
