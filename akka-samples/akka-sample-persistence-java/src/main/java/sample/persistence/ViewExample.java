package sample.persistence;

import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.Duration;

import akka.actor.*;
import akka.persistence.*;

public class ViewExample {
    public static class ExampleProcessor extends UntypedProcessor {
        @Override
        public String processorId() {
            return "processor-5";
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent p = (Persistent)message;
                System.out.println(String.format("processor received %s (sequence nr = %d)", p.payload(), p.sequenceNr()));
            }
        }
    }

    public static class ExampleView extends UntypedView {
        private final ActorRef destination = getContext().actorOf(Props.create(ExampleDestination.class));
        private final ActorRef channel = getContext().actorOf(Channel.props("channel"));

        private int numReplicated = 0;

        @Override
        public String viewId() {
            return "view-5";
        }

        @Override
        public String processorId() {
            return "processor-5";
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent p = (Persistent)message;
                numReplicated += 1;
                System.out.println(String.format("view received %s (sequence nr = %d, num replicated = %d)", p.payload(), p.sequenceNr(), numReplicated));
                channel.tell(Deliver.create(p.withPayload("replicated-" + p.payload()), destination.path()), getSelf());
            } else if (message instanceof SnapshotOffer) {
                SnapshotOffer so = (SnapshotOffer)message;
                numReplicated = (Integer)so.snapshot();
                System.out.println(String.format("view received snapshot offer %s (metadata = %s)", numReplicated, so.metadata()));
            } else if (message.equals("snap")) {
                saveSnapshot(numReplicated);
            }
        }
    }

    public static class ExampleDestination extends UntypedActor {
        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof ConfirmablePersistent) {
                ConfirmablePersistent cp = (ConfirmablePersistent)message;
                System.out.println(String.format("destination received %s (sequence nr = %s)", cp.payload(), cp.sequenceNr()));
                cp.confirm();
            }
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");
        final ActorRef processor = system.actorOf(Props.create(ExampleProcessor.class));
        final ActorRef view = system.actorOf(Props.create(ExampleView.class));

        system.scheduler().schedule(Duration.Zero(), Duration.create(2, TimeUnit.SECONDS), processor, Persistent.create("scheduled"), system.dispatcher(), null);
        system.scheduler().schedule(Duration.Zero(), Duration.create(5, TimeUnit.SECONDS), view, "snap", system.dispatcher(), null);
    }
}
