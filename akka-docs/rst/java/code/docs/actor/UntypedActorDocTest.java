/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

//#import-ask
import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
//#import-ask
//#import-gracefulStop
import static akka.pattern.Patterns.gracefulStop;
//#import-gracefulStop

import akka.actor.PoisonPill;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import akka.testkit.AkkaJUnitActorSystemResource;

import docs.AbstractJavaTest;
import org.junit.ClassRule;
import org.junit.Test;


//#import-gracefulStop
import scala.concurrent.Await;
//#import-ask
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
//#import-ask
//#import-gracefulStop
//#import-indirect
import akka.actor.Actor;
//#import-indirect
//#import-identify
import akka.actor.ActorIdentity;
//#import-identify
import akka.actor.ActorKilledException;
//#import-identify
import akka.actor.ActorSelection;
//#import-identify
//#import-actorRef
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
//#import-actorRef
//#import-identify
import akka.actor.Identify;
//#import-identify
//#import-indirect
import akka.actor.IndirectActorProducer;
//#import-indirect
import akka.actor.OneForOneStrategy;
//#import-props
import akka.actor.Props;
import akka.japi.Creator;
//#import-props
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
//#import-terminated
import akka.actor.Terminated;
//#import-terminated
//#import-untypedActor
import akka.actor.UntypedActor;
//#import-untypedActor
//#import-stash
import akka.actor.UntypedActorWithStash;
//#import-stash
//#import-ask
import akka.dispatch.Futures;
import akka.dispatch.Mapper;
//#import-ask
import akka.japi.Function;
//#import-procedure
import akka.japi.Procedure;
//#import-procedure
//#import-gracefulStop
import akka.pattern.AskTimeoutException;
//#import-gracefulStop
import akka.pattern.Patterns;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
//#import-ask
import akka.util.Timeout;
//#import-ask

public class UntypedActorDocTest extends AbstractJavaTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("UntypedActorDocTest", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  //#creating-props-config
  static class MyActorC implements Creator<MyActor> {
    @Override public MyActor create() {
      return new MyActor("...");
    }
  }
  
  //#creating-props-config

  @SuppressWarnings("unused")
  @Test
  public void createProps() {
    //#creating-props-config
    Props props1 = Props.create(MyUntypedActor.class);
    Props props2 = Props.create(MyActor.class, "...");
    Props props3 = Props.create(new MyActorC());
    //#creating-props-config
  }
  
  //#parametric-creator
  static class ParametricCreator<T extends MyActor> implements Creator<T> {
    @Override public T create() {
      // ... fabricate actor here
      //#parametric-creator
      return null;
      //#parametric-creator
    }
  }
  //#parametric-creator

  @Test
  public void systemActorOf() {
    //#system-actorOf
    // ActorSystem is a heavy object: create only one per application
    final ActorSystem system = ActorSystem.create("MySystem");
    final ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class),
      "myactor");
    //#system-actorOf
    try {
      new JavaTestKit(system) {
        {
          myActor.tell("hello", getRef());
          expectMsgEquals("hello");
        }
      };
    } finally {
      JavaTestKit.shutdownActorSystem(system);
    }
  }

  @Test
  public void contextActorOf() {
    new JavaTestKit(system) {
      {
        //#context-actorOf
        class A extends UntypedActor {
          final ActorRef child =
              getContext().actorOf(Props.create(MyUntypedActor.class), "myChild");
          //#plus-some-behavior
          @Override
          public void onReceive(Object msg) {
            getSender().tell(child, getSelf());
          }
          //#plus-some-behavior
        }
        //#context-actorOf
        final ActorRef top = system.actorOf(Props.create(A.class, this));
        top.tell("hello", getRef());
        final ActorRef child = expectMsgClass(ActorRef.class);
        child.tell("hello", getRef());
        expectMsgEquals("hello");
      }
    };
  }
  
  // this is just to make the test below a tiny fraction nicer
  private ActorSystem getContext() {
    return system;
  }

  static
  //#creating-indirectly
  class DependencyInjector implements IndirectActorProducer {
    final Object applicationContext;
    final String beanName;
    
    public DependencyInjector(Object applicationContext, String beanName) {
      this.applicationContext = applicationContext;
      this.beanName = beanName;
    }
    
    @Override
    public Class<? extends Actor> actorClass() {
      return MyActor.class;
    }
    
    @Override
    public MyActor produce() {
      MyActor result;
      //#obtain-fresh-Actor-instance-from-DI-framework
      result = new MyActor((String) applicationContext);
      //#obtain-fresh-Actor-instance-from-DI-framework
      return result;
    }
  }
  //#creating-indirectly
  
  @Test
  public void indirectActorOf() {
    final String applicationContext = "...";
    //#creating-indirectly
    
    final ActorRef myActor = getContext().actorOf(
      Props.create(DependencyInjector.class, applicationContext, "MyActor"),
        "myactor3");
    //#creating-indirectly
    new JavaTestKit(system) {
      {
        myActor.tell("hello", getRef());
        expectMsgEquals("...");
      }
    };
  }

  @SuppressWarnings("unused")
  @Test
  public void usingAsk() throws Exception {
    ActorRef myActor = system.actorOf(Props.create(MyAskActor.class, this), "myactor5");

    //#using-ask
    Future<Object> future = Patterns.ask(myActor, "Hello", 1000);
    Object result = Await.result(future, Duration.create(1, TimeUnit.SECONDS));
    //#using-ask
  }

  @Test
  public void receiveTimeout() {
    final ActorRef myActor = system.actorOf(Props.create(MyReceiveTimeoutUntypedActor.class));
    new JavaTestKit(system) {
      {
        new Within(Duration.create(1, TimeUnit.SECONDS), Duration.create(1500,
            TimeUnit.MILLISECONDS)) {
          @Override
          protected void run() {
            myActor.tell("Hello", getRef());
            expectMsgEquals("Hello world");
            expectMsgEquals("timeout");
          }
        };
      }
    };
  }

  @Test
  public void usePoisonPill() {
    final ActorRef myActor = system.actorOf(Props.create(MyUntypedActor.class));
    new JavaTestKit(system) {
      {
        final ActorRef sender = getRef();
        //#poison-pill
        myActor.tell(akka.actor.PoisonPill.getInstance(), sender);
        //#poison-pill
        watch(myActor);
        expectTerminated(myActor);
      }
    };
  }

  @Test
  public void useKill() {
    new JavaTestKit(system) {
      {
        class Master extends UntypedActor {
          private SupervisorStrategy strategy = new OneForOneStrategy(-1,
              Duration.Undefined(), new Function<Throwable, Directive>() {
            @Override
            public Directive apply(Throwable thr) {
              if (thr instanceof ActorKilledException) {
                target.tell("killed", getSelf());
                getContext().stop(getSelf());
                return SupervisorStrategy.stop();
              }
              return SupervisorStrategy.escalate();
            }
          });
          final ActorRef target;
          ActorRef child;

          //#preStart
          @Override
          public void preStart() {
            child = getContext().actorOf(Props.empty());
          }
          //#preStart
          
          @SuppressWarnings("unused")
          public Master(ActorRef target) {
            this.target = target;

            /*
             * Only compilation of `forward` is verified here.
             */
            final Object result = "";
            //#forward
            target.forward(result, getContext());
            //#forward
          }
          
          @Override
          public SupervisorStrategy supervisorStrategy() {
            return strategy;
          }

          //#reply
          @Override
          public void onReceive(Object msg) {
            Object result =
                //#calculate-result
                child;
                //#calculate-result
            
            // do not forget the second argument!
            getSender().tell(result, getSelf());
          }
          //#reply
          
          //#postStop
          @Override
          public void postStop() {
            //#clean-up-resources-here
            final String message = "stopped";
            //#tell
            // don’t forget to think about who is the sender (2nd argument)
            target.tell(message, getSelf());
            //#tell
            //#clean-up-resources-here
          }
          //#postStop
        }
        final ActorRef master = system.actorOf(Props.create(Master.class, this, getRef()));
        expectMsgEquals("");
        master.tell("", getRef());
        final ActorRef victim = expectMsgClass(ActorRef.class);
        //#kill
        victim.tell(akka.actor.Kill.getInstance(), ActorRef.noSender());
        //#kill
        expectMsgEquals("killed");
        expectMsgEquals("stopped");
        assert getLastSender().equals(master);
      }
    };
  }

  @Test
  public void useBecome() {
    new JavaTestKit(system) {
      {
        ActorRef myActor = system.actorOf(Props.create(HotSwapActor.class));
        myActor.tell("foo", getRef());
        myActor.tell("bar", getRef());
        expectMsgEquals("I am already happy :-)");
        myActor.tell("bar", getRef());
        expectMsgEquals("I am already happy :-)");
      }
    };
  }

  @Test
  public void useWatch() throws Exception {
    ActorRef myActor = system.actorOf(Props.create(WatchActor.class));
    Future<Object> future = Patterns.ask(myActor, "kill", 1000);
    assert Await.result(future, Duration.create("1 second")).equals("finished");
  }
  
  // compilation test only
  public void compileSelections() {
    //#selection-local
    // will look up this absolute path
    getContext().actorSelection("/user/serviceA/actor");
    // will look up sibling beneath same supervisor
    getContext().actorSelection("../joe");
    //#selection-local

    //#selection-wildcard
    // will look all children to serviceB with names starting with worker
    getContext().actorSelection("/user/serviceB/worker*");
    // will look up all siblings beneath same supervisor
    getContext().actorSelection("../*");
    //#selection-wildcard
    
    //#selection-remote
    getContext().actorSelection("akka.tcp://app@otherhost:1234/user/serviceB");
    //#selection-remote
  }

  @Test
  public void useIdentify() throws Exception {
    new JavaTestKit(system) {
      {
        ActorRef a = system.actorOf(Props.create(MyUntypedActor.class), "another");
        ActorRef b = system.actorOf(Props.create(Follower.class, getRef()));
        expectMsgEquals(a);
        system.stop(a);
        watch(b);
        expectTerminated(b);
      }
    };
  }

  @Test
  public void usePatternsGracefulStop() throws Exception {
    ActorRef actorRef = system.actorOf(Props.create(Manager.class));
    //#gracefulStop
    try {
      Future<Boolean> stopped =
        gracefulStop(actorRef, Duration.create(5, TimeUnit.SECONDS), Manager.SHUTDOWN);
      Await.result(stopped, Duration.create(6, TimeUnit.SECONDS));
      // the actor has been stopped
    } catch (AskTimeoutException e) {
      // the actor wasn't stopped within 5 seconds
    }
    //#gracefulStop
  }

  class Result {
    final String x;
    final String s;

    public Result(String x, String s) {
      this.x = x;
      this.s = s;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((s == null) ? 0 : s.hashCode());
      result = prime * result + ((x == null) ? 0 : x.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      Result other = (Result) obj;
      if (s == null) {
        if (other.s != null)
          return false;
      } else if (!s.equals(other.s))
        return false;
      if (x == null) {
        if (other.x != null)
          return false;
      } else if (!x.equals(other.x))
        return false;
      return true;
    }
  }

  @Test
  public void usePatternsAskPipe() {
    new JavaTestKit(system) {
      {
        ActorRef actorA = system.actorOf(Props.create(MyUntypedActor.class));
        ActorRef actorB = system.actorOf(Props.create(MyUntypedActor.class));
        ActorRef actorC = getRef();

        //#ask-pipe
        final Timeout t = new Timeout(Duration.create(5, TimeUnit.SECONDS));

        final ArrayList<Future<Object>> futures = new ArrayList<Future<Object>>();
        futures.add(ask(actorA, "request", 1000)); // using 1000ms timeout
        futures.add(ask(actorB, "another request", t)); // using timeout from
                                                        // above

        final Future<Iterable<Object>> aggregate = Futures.sequence(futures,
            system.dispatcher());

        final Future<Result> transformed = aggregate.map(
            new Mapper<Iterable<Object>, Result>() {
              public Result apply(Iterable<Object> coll) {
                final Iterator<Object> it = coll.iterator();
                final String x = (String) it.next();
                final String s = (String) it.next();
                return new Result(x, s);
              }
            }, system.dispatcher());

        pipe(transformed, system.dispatcher()).to(actorC);
        //#ask-pipe
        
        expectMsgEquals(new Result("request", "another request"));
      }
    };
  }

  static
  //#props-factory
  public class DemoActor extends UntypedActor {
    
    /**
     * Create Props for an actor of this type.
     * @param magicNumber The magic number to be passed to this actor’s constructor.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    public static Props props(final int magicNumber) {
      return Props.create(new Creator<DemoActor>() {
        private static final long serialVersionUID = 1L;

        @Override
        public DemoActor create() throws Exception {
          return new DemoActor(magicNumber);
        }
      });
    }
    
    final int magicNumber;

    public DemoActor(int magicNumber) {
      this.magicNumber = magicNumber;
    }
    
    @Override
    public void onReceive(Object msg) {
      // some behavior here
    }
    
  }
  
  //#props-factory
  @Test
  public void demoActor() {
    //#props-factory
    system.actorOf(DemoActor.props(42), "demo");
    //#props-factory
  }

  static
  //#messages-in-companion
  public class DemoMessagesActor extends UntypedActor {

    static public class Greeting {
      private final String from;

      public Greeting(String from) {
        this.from = from;
      }

      public String getGreeter() {
        return from;
      }
    }

    public void onReceive(Object message) throws Exception {
      if (message instanceof Greeting) {
        getSender().tell("Hello " + ((Greeting) message).getGreeter(), getSelf());
      } else
        unhandled(message);
    }
  }
  //#messages-in-companion

  public static class MyActor extends UntypedActor {

    final String s;
    
    public MyActor(String s) {
      this.s = s;
    }

    public void onReceive(Object message) {
      getSender().tell(s, getSelf());
    }

    /*
     * This section must be kept in sync with the actual Actor trait.
     * 
     * BOYSCOUT RULE: whenever you read this, verify that!
     */
    //#lifecycle-callbacks
    public void preStart() {
    }

    public void preRestart(Throwable reason, scala.Option<Object> message) {
      for (ActorRef each : getContext().getChildren()) {
        getContext().unwatch(each);
        getContext().stop(each);
      }
      postStop();
    }

    public void postRestart(Throwable reason) {
      preStart();
    }

    public void postStop() {
    }
    //#lifecycle-callbacks
  }

  public class MyAskActor extends UntypedActor {

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
  //#gracefulStop-actor
  public class Manager extends UntypedActor {
    
    public static final String SHUTDOWN = "shutdown";
    
    ActorRef worker = getContext().watch(getContext().actorOf(
        Props.create(Cruncher.class), "worker"));
    
    public void onReceive(Object message) {
      if (message.equals("job")) {
        worker.tell("crunch", getSelf());
      } else if (message.equals(SHUTDOWN)) {
        worker.tell(PoisonPill.getInstance(), getSelf());
        getContext().become(shuttingDown);
      }
    }
    
    Procedure<Object> shuttingDown = new Procedure<Object>() {
      @Override
      public void apply(Object message) {
        if (message.equals("job")) {
          getSender().tell("service unavailable, shutting down", getSelf());
        } else if (message instanceof Terminated) {
          getContext().stop(getSelf());
        }
      }
    };
  }
  //#gracefulStop-actor
  
  static class Cruncher extends UntypedActor {
    public void onReceive(Object message) {
     // crunch...
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
    final String identifyId = "1";
    {
      ActorSelection selection =
        getContext().actorSelection("/user/another");
      selection.tell(new Identify(identifyId), getSelf());
    }
    ActorRef another;
    
    //#test-omitted
    final ActorRef probe;
    public Follower(ActorRef probe) {
      this.probe = probe;
    }
    //#test-omitted

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
            //#test-omitted
            probe.tell(ref, getSelf());
            //#test-omitted
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
