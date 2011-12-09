package akka.docs.actor;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

//#imports

//#import-future
import akka.dispatch.Future;

//#import-future

//#import-actors
import static akka.actor.Actors.*;

//#import-actors

//#import-procedure
import akka.japi.Procedure;

//#import-procedure

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.MessageDispatcher;

import org.junit.Test;

import scala.Option;

import static org.junit.Assert.*;

public class UntypedActorTestBase {

  @Test
  public void systemActorOf() {
    //#system-actorOf
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(MyUntypedActor.class);
    //#system-actorOf
    myActor.tell("test");
    system.stop();
  }

  @Test
  public void contextActorOf() {
    //#context-actorOf
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(MyUntypedActor.class);
    //#context-actorOf
    myActor.tell("test");
    system.stop();
  }

  @Test
  public void constructorActorOf() {
    ActorSystem system = ActorSystem.create("MySystem");
    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    ActorRef myActor = system.actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyActor("...");
      }
    });
    //#creating-constructor
    myActor.tell("test");
    system.stop();
  }

  @Test
  public void propsActorOf() {
    ActorSystem system = ActorSystem.create("MySystem");
    //#creating-props
    MessageDispatcher dispatcher = system.dispatcherFactory().newFromConfig("my-dispatcher");
    ActorRef myActor = system.actorOf(new Props().withCreator(MyUntypedActor.class).withDispatcher(dispatcher),
        "myactor");
    //#creating-props
    myActor.tell("test");
    system.stop();
  }

  @Test
  public void usingAsk() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyAskActor();
      }
    });

    //#using-ask
    Future future = myActor.ask("Hello", 1000);
    future.await();
    if (future.isCompleted()) {
      Option resultOption = future.result();
      if (resultOption.isDefined()) {
        Object result = resultOption.get();
        // ...
      } else {
        //...  whatever
      }
    }
    //#using-ask
    system.stop();
  }

  @Test
  public void receiveTimeout() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(MyReceivedTimeoutUntypedActor.class);
    myActor.tell("Hello");
    system.stop();
  }

  @Test
  public void usePoisonPill() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(MyUntypedActor.class);
    //#poison-pill
    myActor.tell(poisonPill());
    //#poison-pill
    system.stop();
  }

  @Test
  public void useKill() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef victim = system.actorOf(MyUntypedActor.class);
    //#kill
    victim.tell(kill());
    //#kill
    system.stop();
  }

  @Test
  public void useBecome() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new UntypedActorFactory() {
      public UntypedActor create() {
        return new HotSwapActor();
      }
    });
    myActor.tell("foo");
    myActor.tell("bar");
    myActor.tell("bar");
    system.stop();
  }

  public static class MyActor extends UntypedActor {

    public MyActor(String s) {
    }

    public void onReceive(Object message) throws Exception {
      try {
        operation();
      } catch (Exception e) {
        getSender().tell(new akka.actor.Status.Failure(e));
        throw e;
      }
    }

    private void operation() {
    }

    //#lifecycle-callbacks
    public void preStart() {
    }

    public void preRestart(Throwable reason, Option<Object> message) {
      postStop();
    }

    public void postRestart(Throwable reason) {
      preStart();
    }

    public void postStop() {
    }
    //#lifecycle-callbacks
  }

  public static class MyAskActor extends UntypedActor {

    public void onReceive(Object message) throws Exception {
      //#reply-exception
      try {
        String result = operation();
        getSender().tell(result);
      } catch (Exception e) {
        getSender().tell(new akka.actor.Status.Failure(e));
        throw e;
      }
      //#reply-exception
    }

    private String operation() {
      return "Hi";
    }
  }

  //#hot-swap-actor
  public static class HotSwapActor extends UntypedActor {

    Procedure<Object> angry = new Procedure<Object>() {
      @Override
      public void apply(Object message) {
        if (message.equals("foo")) {
          getSender().tell("I am already angry?");
        } else if (message.equals("foo")) {
          getContext().become(happy);
        }
      }
    };

    Procedure<Object> happy = new Procedure<Object>() {
      @Override
      public void apply(Object message) {
        if (message.equals("bar")) {
          getSender().tell("I am already happy :-)");
        } else if (message.equals("foo")) {
          getContext().become(angry);
        }
      }
    };

    public void onReceive(Object message) {
      if (message.equals("bar")) {
        getContext().become(angry);
      } else if (message.equals("foo")) {
        getContext().become(happy);
      }
    }
  }
  //#hot-swap-actor

}
