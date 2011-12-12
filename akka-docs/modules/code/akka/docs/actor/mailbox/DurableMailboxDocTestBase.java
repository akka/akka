package akka.docs.actor.mailbox;

//#imports
import akka.actor.mailbox.DurableMailboxType;
import akka.dispatch.MessageDispatcher;
import akka.actor.UntypedActorFactory;
import akka.actor.UntypedActor;
import akka.actor.Props;

//#imports

import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;

import static org.junit.Assert.*;

public class DurableMailboxDocTestBase {

  @Test
  public void defineDispatcher() {
    ActorSystem system = ActorSystem.create("MySystem");
    //#define-dispatcher
    MessageDispatcher dispatcher = system.dispatcherFactory()
        .newDispatcher("my-dispatcher", 1, DurableMailboxType.fileDurableMailboxType()).build();
    ActorRef myActor = system.actorOf(new Props().withDispatcher(dispatcher).withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyUntypedActor();
      }
    }));
    //#define-dispatcher
    myActor.tell("test");
    system.stop();
  }

  public static class MyUntypedActor extends UntypedActor {
    public void onReceive(Object message) {
    }
  }
}
