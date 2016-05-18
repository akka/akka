package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.Procedure;
import akka.persistence.UntypedPersistentActor;

import java.util.ArrayList;

public class PersistentActorFailureExample {
  public static class ExamplePersistentActor extends UntypedPersistentActor {
    @Override
    public String persistenceId() { return "sample-id-2"; }

    private ArrayList<Object> received = new ArrayList<Object>();

    @Override
    public void onReceiveCommand(Object message) throws Exception {
      if (message.equals("boom")) {
        throw new Exception("boom");
      } else if (message.equals("print")) {
        System.out.println("received " + received);
      } else if (message instanceof String) {
        String s = (String) message;
        persist(s, new Procedure<String>() {
          public void apply(String evt) throws Exception {
            received.add(evt);
          }
        });
      } else {
        unhandled(message);
      }
    }
    
    @Override
    public void onReceiveRecover(Object message) {
      if (message instanceof String) {
        received.add((String) message);
      } else {
        unhandled(message);
      }
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");
    final ActorRef persistentActor = system.actorOf(Props.create(ExamplePersistentActor.class), "persistentActor-2");

    persistentActor.tell("a", null);
    persistentActor.tell("print", null);
    persistentActor.tell("boom", null);
    persistentActor.tell("print", null);
    persistentActor.tell("b", null);
    persistentActor.tell("print", null);
    persistentActor.tell("c", null);
    persistentActor.tell("print", null);

    // Will print in a first run (i.e. with empty journal):

    // received [a]
    // received [a, b]
    // received [a, b, c]

    // Will print in a second run:

    // received [a, b, c, a]
    // received [a, b, c, a, b]
    // received [a, b, c, a, b, c]

    // etc ...

    Thread.sleep(10000);
    system.terminate();
  }
}
