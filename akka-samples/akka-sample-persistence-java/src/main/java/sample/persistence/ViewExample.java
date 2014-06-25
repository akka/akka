package sample.persistence;

import java.util.concurrent.TimeUnit;
import scala.concurrent.duration.Duration;

import akka.actor.*;
import akka.persistence.*;
import akka.japi.Procedure;

public class ViewExample {
    public static class ExamplePersistentActor extends UntypedPersistentActor {
        private int count = 1;
      
        @Override
        public String persistenceId() {
            return "persistentActor-5";
        }
        
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

    public static class ExampleView extends UntypedView {

        private int numReplicated = 0;

        @Override
        public String viewId() {
            return "view-5";
        }

        @Override
        public String persistenceId() {
            return "persistentActor-5";
        }

        @Override
        public void onReceive(Object message) throws Exception {
            if (message instanceof Persistent) {
                Persistent p = (Persistent)message;
                numReplicated += 1;
                System.out.println(String.format("view received %s (num replicated = %d)", p.payload(), numReplicated));
            } else if (message instanceof SnapshotOffer) {
                SnapshotOffer so = (SnapshotOffer)message;
                numReplicated = (Integer)so.snapshot();
                System.out.println(String.format("view received snapshot offer %s (metadata = %s)", numReplicated, so.metadata()));
            } else if (message.equals("snap")) {
                saveSnapshot(numReplicated);
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
