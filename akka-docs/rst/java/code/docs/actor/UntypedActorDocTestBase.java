/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#imports
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
//#imports

//#import-future
import scala.concurrent.Future;
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.testkit.AkkaSpec;
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

//#import-identify
import akka.actor.ActorSelection;
import akka.actor.Identify;
import akka.actor.ActorIdentity;
//#import-identify

//#import-gracefulStop
import static akka.pattern.Patterns.gracefulStop;
import scala.concurrent.Future;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.pattern.AskTimeoutException;
//#import-gracefulStop

//#import-askPipe
import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
import scala.concurrent.Future;
import akka.dispatch.Futures;
import scala.concurrent.duration.Duration;
import akka.util.Timeout;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
//#import-askPipe

//#import-stash
import akka.actor.UntypedActorWithStash;
//#import-stash

import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;

import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import scala.Option;
import java.lang.Object;
import java.util.Iterator;
import akka.pattern.Patterns;

public class UntypedActorDocTestBase {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("MySystem", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

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
    myActor.tell("test", null);
    system.shutdown();
  }

  @Test
  public void contextActorOf() {
    //#context-actorOf
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class), "myactor2");
    //#context-actorOf
    myActor.tell("test", null);
  }

  @Test
  public void constructorActorOf() {
    //#creating-constructor
    // allows passing in arguments to the MyActor constructor
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyActor("...");
      }
    }), "myactor3");
    //#creating-constructor
    myActor.tell("test", null);
  }

  @Test
  public void propsActorOf() {
    //#creating-props
    ActorRef myActor = system.actorOf(
      new Props(MyUntypedActor.class).withDispatcher("my-dispatcher"), "myactor4");
    //#creating-props
    myActor.tell("test", null);
  }

  @Test
  public void usingAsk() throws Exception {
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new MyAskActor();
      }
    }), "myactor5");

    //#using-ask
    Future<Object> future = Patterns.ask(myActor, "Hello", 1000);
    Object result = Await.result(future, Duration.create(1, TimeUnit.SECONDS));
    //#using-ask
  }

  @Test
  public void receiveTimeout() {
    ActorRef myActor = system.actorOf(new Props(MyReceivedTimeoutUntypedActor.class));
    myActor.tell("Hello", null);
  }

  @Test
  public void usePoisonPill() {
    ActorRef myActor = system.actorOf(new Props(MyUntypedActor.class));
    //#poison-pill
    myActor.tell(PoisonPill.getInstance(), null);
    //#poison-pill
  }

  @Test
  public void useKill() {
    ActorRef victim = system.actorOf(new Props(MyUntypedActor.class));
    //#kill
    victim.tell(Kill.getInstance(), null);
    //#kill
  }

  @Test
  public void useBecome() {
    ActorRef myActor = system.actorOf(new Props(new UntypedActorFactory() {
      public UntypedActor create() {
        return new HotSwapActor();
      }
    }));
    myActor.tell("foo", null);
    myActor.tell("bar", null);
    myActor.tell("bar", null);
  }

  @Test
  public void useWatch() throws Exception {
    ActorRef myActor = system.actorOf(new Props(WatchActor.class));
    Future<Object> future = Patterns.ask(myActor, "kill", 1000);
    assert Await.result(future, Duration.create("1 second")).equals("finished");
  }

  @Test
  public void useIdentify() throws Exception {
    ActorRef a = system.actorOf(new Props(MyUntypedActor.class), "another");
    ActorRef b = system.actorOf(new Props(Follower.class));
    system.stop(a);
  }

  @Test
  public void usePatternsGracefulStop() throws Exception {
    ActorRef actorRef = system.actorOf(new Props(MyUntypedActor.class));
    //#gracefulStop
    try {
      Future<Boolean> stopped =
        gracefulStop(actorRef, Duration.create(5, TimeUnit.SECONDS));
      Await.result(stopped, Duration.create(6, TimeUnit.SECONDS));
      // the actor has been stopped
    } catch (AskTimeoutException e) {
      // the actor wasn't stopped within 5 seconds
    }
    //#gracefulStop
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
    ActorRef actorA = system.actorOf(new Props(MyUntypedActor.class));
    ActorRef actorB = system.actorOf(new Props(MyUntypedActor.class));
    ActorRef actorC = system.actorOf(new Props(MyUntypedActor.class));
    //#ask-pipe
    final Timeout t = new Timeout(Duration.create(5, TimeUnit.SECONDS));

    final ArrayList<Future<Object>> futures = new ArrayList<Future<Object>>();
    futures.add(ask(actorA, "request", 1000)); // using 1000ms timeout
    futures.add(ask(actorB, "another request", t)); // using timeout from above

    final Future<Iterable<Object>> aggregate =
      Futures.sequence(futures, system.dispatcher());

    final Future<Result> transformed = aggregate.map(
      new Mapper<Iterable<Object>, Result>() {
        public Result apply(Iterable<Object> coll) {
          final Iterator<Object> it = coll.iterator();
          final String s = (String) it.next();
          final int x = (Integer) it.next();
          return new Result(x, s);
        }
      }, system.dispatcher());

    pipe(transformed, system.dispatcher()).to(actorC);
    //#ask-pipe
  }

  public static class MyActor extends UntypedActor {

    public MyActor(String s) {
    }

    public void onReceive(Object message) throws Exception {
      try {
        operation();
      } catch (Exception e) {
        getSender().tell(new akka.actor.Status.Failure(e), getSelf());
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
        getSender().tell(result, getSelf());
      } catch (Exception e) {
        getSender().tell(new akka.actor.Status.Failure(e), getSelf());
        throw e;
      }
      //#reply-exception
    }

    private String operation() {
      return "Hi";
    }
  }

  static
  //#hot-swap-actor
  public class HotSwapActor extends UntypedActor {

    Procedure<Object> angry = new Procedure<Object>() {
      @Override
      public void apply(Object message) {
        if (message.equals("bar")) {
          getSender().tell("I am already angry?", getSelf());
        } else if (message.equals("foo")) {
          getContext().become(happy);
        }
      }
    };

    Procedure<Object> happy = new Procedure<Object>() {
      @Override
      public void apply(Object message) {
        if (message.equals("bar")) {
          getSender().tell("I am already happy :-)", getSelf());
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

  static
  //#stash
  public class ActorWithProtocol extends UntypedActorWithStash {
    public void onReceive(Object msg) {
      if (msg.equals("open")) {
        unstashAll();
        getContext().become(new Procedure<Object>() {
          public void apply(Object msg) throws Exception {
            if (msg.equals("write")) {
              // do writing...
            } else if (msg.equals("close")) {
              unstashAll();
              getContext().unbecome();
            } else {
              stash();
            }
          }
        }, false); // add behavior on top instead of replacing
      } else {
        stash();
      }
    }
  }
  //#stash

  static
  //#watch
  public class WatchActor extends UntypedActor {
    final ActorRef child = this.getContext().actorOf(Props.empty(), "child");
    {
      this.getContext().watch(child); // <-- the only call needed for registration
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
          lastSender.tell("finished", getSelf());
        }
      } else {
        unhandled(message);
      }
    }
  }
  //#watch

  static
  //#identify
  public class Follower extends UntypedActor {
    String identifyId = "1";
    {
      ActorSelection selection =
        getContext().actorSelection("/user/another");
      selection.tell(new Identify(identifyId), getSelf());
    }
    ActorRef another;

    @Override
    public void onReceive(Object message) {
      if (message instanceof ActorIdentity) {
        ActorIdentity identity = (ActorIdentity) message;
        if (identity.correlationId().equals(identifyId)) {
          ActorRef ref = identity.getRef();
          if (ref == null)
            getContext().stop(getSelf());
          else {
            another = ref;
            getContext().watch(another);
          }
        }
      } else if (message instanceof Terminated) {
        final Terminated t = (Terminated) message;
        if (t.getActor().equals(another)) {
          getContext().stop(getSelf());
        }
      } else {
        unhandled(message);
      }
    }
  }
  //#identify

}
