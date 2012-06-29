/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
//#imports

//#import-future
import akka.dispatch.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import scala.concurrent.Await;
import scala.concurrent.util.Duration;
import akka.util.Timeout;
//#import-future

//#import-actors
import akka.actor.PoisonPill;
import akka.actor.Kill;
//#import-actors

//#import-procedure
import akka.japi.Procedure;
//#import-procedure

//#import-watch
import akka.actor.Terminated;
//#import-watch

//#import-gracefulStop
import static akka.pattern.Patterns.gracefulStop;
import akka.dispatch.Future;
import scala.concurrent.Await;
import scala.concurrent.util.Duration;
import akka.pattern.AskTimeoutException;
//#import-gracefulStop

//#import-askPipe
import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
import akka.dispatch.Future;
import akka.dispatch.Futures;
import scala.concurrent.util.Duration;
import akka.util.Timeout;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
//#import-askPipe

import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.dispatch.MessageDispatcher;

import org.junit.Test;
import scala.Option;
import java.lang.Object;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import akka.pattern.Patterns;

import static org.junit.Assert.*;

public class UntypedActorDocTestBase {

  @Test
  public void createProps() {
    //#creating-props-config
    Props props1 = new Props();
    Props props2 = new Props(MyUntypedActor.class);
    Props props3 = new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyUntypedActor();
      }
    });
    Props props4 = props1.withCreator(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyUntypedActor();
      }
    });
    //#creating-props-config
  }

  @Test
  public void systemActorOf() {
    //#system-actorOf
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class), "myactor");
    //#system-actorOf
    myActor.tell("test");
    system.shutdown();
  }

  @Test
  public void contextActorOf() {
    //#context-actorOf
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class), "myactor");
    //#context-actorOf
    myActor.tell("test");
    system.shutdown();
  }

  @Test
  public void constructorActorOf() {
    ActorSystem system = ActorSystem.create("MySystem");
    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyActor("...");
      }
    }), "myactor");
    //#creating-constructor
    myActor.tell("test");
    system.shutdown();
  }

  @Test
  public void propsActorOf() {
    ActorSystem system = ActorSystem.create("MySystem");
    //#creating-props
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class).withDispatcher("my-dispatcher"), "myactor");
    //#creating-props
    myActor.tell("test");
    system.shutdown();
  }

  @Test
  public void usingAsk() throws Exception {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyAskActor();
      }
    }), "myactor");

    //#using-ask
    Future<Object> future = Patterns.ask(myActor, "Hello", 1000);
    Object result = Await.result(future, Duration.create(1, TimeUnit.SECONDS));
    //#using-ask
    system.shutdown();
  }

  @Test
  public void receiveTimeout() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(MyReceivedTimeoutUntypedActor.class));
    myActor.tell("Hello");
    system.shutdown();
  }

  @Test
  public void usePoisonPill() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class));
    //#poison-pill
    myActor.tell(PoisonPill.getInstance());
    //#poison-pill
    system.shutdown();
  }

  @Test
  public void useKill() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef victim = system.actorOf(new Props(MyUntypedActor.class));
    //#kill
    victim.tell(Kill.getInstance());
    //#kill
    system.shutdown();
  }

  @Test
  public void useBecome() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new HotSwapActor();
      }
    }));
    myActor.tell("foo");
    myActor.tell("bar");
    myActor.tell("bar");
    system.shutdown();
  }

  @Test
  public void useWatch() throws Exception {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef myActor = system.actorOf(new Props(WatchActor.class));
    Future<Object> future = Patterns.ask(myActor, "kill", 1000);
    assert Await.result(future, Duration.parse("1 second")).equals("finished");
    system.shutdown();
  }

  @Test
  public void usePatternsGracefulStop() throws Exception {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef actorRef = system.actorOf(new Props(MyUntypedActor.class));
    //#gracefulStop
    //FIXME URGENT Await.result should have a @throws clause
    //try {
      Future<Boolean> stopped = gracefulStop(actorRef, Duration.create(5, TimeUnit.SECONDS), system);
      Await.result(stopped, Duration.create(6, TimeUnit.SECONDS));
      // the actor has been stopped
    //} catch (AskTimeoutException e) {
      // the actor wasn't stopped within 5 seconds
    //}
    //#gracefulStop
    system.shutdown();
  }

  class Result {
    final int x;
    final String s;

    public Result(int x, String s) {
      this.x = x;
      this.s = s;
    }
  }

  @Test
  public void usePatternsAskPipe() {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef actorA = system.actorOf(new Props(MyUntypedActor.class));
    ActorRef actorB = system.actorOf(new Props(MyUntypedActor.class));
    ActorRef actorC = system.actorOf(new Props(MyUntypedActor.class));
    //#ask-pipe
    final Timeout t = new Timeout(Duration.create(5, TimeUnit.SECONDS));

    final ArrayList<Future<Object>> futures = new ArrayList<Future<Object>>();
    futures.add(ask(actorA, "request", 1000)); // using 1000ms timeout
    futures.add(ask(actorB, "reqeest", t)); // using timeout from above

    final Future<Iterable<Object>> aggregate = Futures.sequence(futures, system.dispatcher());
    
    final Future<Result> transformed = aggregate.map(new Mapper<Iterable<Object>, Result>() {
      public Result apply(Iterable<Object> coll) {
        final Iterator<Object> it = coll.iterator();
        final String s = (String) it.next();
        final int x = (Integer) it.next();
        return new Result(x, s);
      }
    });

    pipe(transformed).to(actorC);
    //#ask-pipe
    system.shutdown();
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
      for (ActorRef each : getContext().getChildren())
        getContext().stop(each);
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
      } else {
        unhandled(message);
      }
    }
  }

  //#hot-swap-actor

  //#watch
  public static class WatchActor extends UntypedActor {
    final ActorRef child = this.getContext().actorOf(Props.empty(), "child");
    {
      this.getContext().watch(child); // <-- this is the only call needed for registration
    }
    ActorRef lastSender = getContext().system().deadLetters();

    @Override
    public void onReceive(Object message) {
      if (message.equals("kill")) {
        getContext().stop(child);
        lastSender = getSender();
      } else if (message instanceof Terminated) {
        final Terminated t = (Terminated) message;
        if (t.getActor() == child) {
          lastSender.tell("finished");
        }
      } else {
        unhandled(message);
      }
    }
  }
  //#watch

}
