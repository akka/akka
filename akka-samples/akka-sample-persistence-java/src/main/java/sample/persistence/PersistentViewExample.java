package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Procedure;
import akka.persistence.SnapshotOffer;
import akka.persistence.UntypedPersistentActor;
import akka.persistence.UntypedPersistentView;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;

public class PersistentViewExample {
    public static class ExamplePersistentActor extends UntypedPersistentActor {
        @Override
        public String persistenceId() { return "sample-id-4"; }

        private int count = 1;

        @Override
        public void onReceiveRecover(Object message) {
          if (message instanceof String) {
            count += 1;
          } else {
            unhandled(message);
          }
        }

        @Override
        public void onReceiveCommand(Object message) {
          if (message instanceof String) {
            String s = (String) message;
            System.out.println(String.format("persistentActor received %s (nr = %d)", s, count));
            persist(s + count, new Procedure<String>() {
              public void apply(String evt) {
                count += 1;
              }
            });
          } else {
            unhandled(message);
          }
        }
    }

    public static class ExampleView extends UntypedPersistentView {

        private int numReplicated = 0;

        @Override public String persistenceId() { return "sample-id-4"; }
        @Override public String viewId() { return "sample-view-id-4"; }

        @Override
        public void onReceive(Object message) throws Exception {
            if (isPersistent()) {
                numReplicated += 1;
                System.out.println(String.format("view received %s (num replicated = %d)", message, numReplicated));
            } else if (message instanceof SnapshotOffer) {
                SnapshotOffer so = (SnapshotOffer)message;
                numReplicated = (Integer)so.snapshot();
                System.out.println(String.format("view received snapshot offer %s (metadata = %s)", numReplicated, so.metadata()));
            } else if (message.equals("snap")) {
                saveSnapshot(numReplicated);
            } else {
              unhandled(message);
            }
        }
    }

    public static void main(String... args) throws Exception {
        final ActorSystem system = ActorSystem.create("example");
        final ActorRef persistentActor = system.actorOf(Props.create(ExamplePersistentActor.class));
        final ActorRef view = system.actorOf(Props.create(ExampleView.class));

        system.scheduler().schedule(Duration.Zero(), Duration.create(2, TimeUnit.SECONDS), persistentActor, "scheduled", system.dispatcher(), null);
        system.scheduler().schedule(Duration.Zero(), Duration.create(5, TimeUnit.SECONDS), view, "snap", system.dispatcher(), null);
    }
}
