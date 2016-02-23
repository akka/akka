/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

import akka.actor.*;
import akka.japi.pf.ReceiveBuilder;
import akka.testkit.ErrorFilter;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import docs.AbstractJavaTest;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import static docs.actorlambda.Messages.Swap.Swap;
import static docs.actorlambda.Messages.*;
import static akka.japi.Util.immutableSeq;

import java.util.concurrent.TimeUnit;

import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

//#import-props
import akka.actor.Props;
//#import-props
//#import-actorRef
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
//#import-actorRef
//#import-identify
import akka.actor.ActorIdentity;
import akka.actor.ActorSelection;
import akka.actor.Identify;
//#import-identify
//#import-graceFulStop
import akka.pattern.AskTimeoutException;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.Future;
import static akka.pattern.Patterns.gracefulStop;
//#import-graceFulStop

public class ActorDocTest extends AbstractJavaTest {

  public static Config config = ConfigFactory.parseString(
    "akka {\n" +
      "  loggers = [\"akka.testkit.TestEventListener\"]\n" +
      "  loglevel = \"WARNING\"\n" +
      "  stdout-loglevel = \"WARNING\"\n" +
      "}\n"
  );

  static ActorSystem system = null;

  @BeforeClass
  public static void beforeClass() {
    system = ActorSystem.create("ActorDocTest", config);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    Await.ready(system.terminate(), Duration.create("5 seconds"));
  }

  static
  //#context-actorOf
  public class FirstActor extends AbstractActor {
    final ActorRef child = context().actorOf(Props.create(MyActor.class), "myChild");
    //#plus-some-behavior
    public FirstActor() {
      receive(ReceiveBuilder.
        matchAny(x -> {
          sender().tell(x, self());
        }).build()
      );
    }
    //#plus-some-behavior
  }
  //#context-actorOf

  static public abstract class SomeActor extends AbstractActor {
    //#receive-constructor
    public SomeActor() {
      receive(ReceiveBuilder.
        //#and-some-behavior
        match(String.class, s -> System.out.println(s.toLowerCase())).
        //#and-some-behavior
      build());
    }
    //#receive-constructor
    @Override
    //#receive
    public abstract PartialFunction<Object, BoxedUnit> receive();
    //#receive
  }

  static public class ActorWithArgs extends AbstractActor {
    private final String args;

    ActorWithArgs(String args) {
      this.args = args;
      receive(ReceiveBuilder.
        matchAny(x -> { }).build()
      );
    }
  }

  static
  //#props-factory
  public class DemoActor extends AbstractActor {
    /**
     * Create Props for an actor of this type.
     * @param magicNumber The magic number to be passed to this actor’s constructor.
     * @return a Props for creating this actor, which can then be further configured
     *         (e.g. calling `.withDispatcher()` on it)
     */
    static Props props(Integer magicNumber) {
      // You need to specify the actual type of the returned actor
      // since Java 8 lambdas have some runtime type information erased
      return Props.create(DemoActor.class, () -> new DemoActor(magicNumber));
    }

    private final Integer magicNumber;

    DemoActor(Integer magicNumber) {
      this.magicNumber = magicNumber;
      receive(ReceiveBuilder.
        match(Integer.class, i -> {
          sender().tell(i + magicNumber, self());
        }).build()
      );
    }
  }

  //#props-factory
  static
  //#props-factory
  public class SomeOtherActor extends AbstractActor {
    // Props(new DemoActor(42)) would not be safe
    ActorRef demoActor = context().actorOf(DemoActor.props(42), "demo");
    // ...
    //#props-factory
    public SomeOtherActor() {
      receive(emptyBehavior());
    }
    //#props-factory
  }
  //#props-factory

  static
  //#messages-in-companion
  public class DemoMessagesActor extends AbstractLoggingActor {

    static public class Greeting {
      private final String from;

      public Greeting(String from) {
        this.from = from;
      }

      public String getGreeter() {
        return from;
      }
    }

    DemoMessagesActor() {
      receive(ReceiveBuilder.
        match(Greeting.class, g -> {
          log().info("I was greeted by {}", g.getGreeter());
        }).build()
      );
    };
  }
  //#messages-in-companion

  public static class Hook extends AbstractActor {
    ActorRef target = null;
    public Hook() {
      receive(emptyBehavior());
    }
    //#preStart
    @Override
    public void preStart() {
      target = context().actorOf(Props.create(MyActor.class, "target"));
    }
    //#preStart
    //#postStop
    @Override
    public void postStop() {
      //#clean-up-some-resources
      final String message = "stopped";
      //#tell
      // don’t forget to think about who is the sender (2nd argument)
      target.tell(message, self());
      //#tell
      final Object result = "";
      //#forward
      target.forward(result, context());
      //#forward
      target = null;
      //#clean-up-some-resources
    }
    //#postStop

    // compilation test only
    public void compileSelections() {
      //#selection-local
      // will look up this absolute path
      context().actorSelection("/user/serviceA/actor");
      // will look up sibling beneath same supervisor
      context().actorSelection("../joe");
      //#selection-local

      //#selection-wildcard
      // will look all children to serviceB with names starting with worker
      context().actorSelection("/user/serviceB/worker*");
      // will look up all siblings beneath same supervisor
      context().actorSelection("../*");
      //#selection-wildcard

      //#selection-remote
      context().actorSelection("akka.tcp://app@otherhost:1234/user/serviceB");
      //#selection-remote
    }
  }

  public static class ReplyException extends AbstractActor {
    public ReplyException() {
      receive(ReceiveBuilder.
        matchAny(x -> {
          //#reply-exception
          try {
            String result = operation();
            sender().tell(result, self());
          } catch (Exception e) {
            sender().tell(new akka.actor.Status.Failure(e), self());
            throw e;
          }
          //#reply-exception
        }).build()
      );
    }

    private String operation() {
      return "Hi";
    }
  }

  static
  //#gracefulStop-actor
  public class Manager extends AbstractActor {
    private static enum Shutdown {
      Shutdown
    }
    public static final Shutdown SHUTDOWN = Shutdown.Shutdown;

    private ActorRef worker =
    context().watch(context().actorOf(Props.create(Cruncher.class), "worker"));

    public Manager() {
      receive(ReceiveBuilder.
        matchEquals("job", s -> {
          worker.tell("crunch", self());
        }).
        matchEquals(SHUTDOWN, x -> {
          worker.tell(PoisonPill.getInstance(), self());
          context().become(shuttingDown);
        }).build()
      );
    }

    public PartialFunction<Object, BoxedUnit> shuttingDown =
      ReceiveBuilder.
        matchEquals("job", s -> {
          sender().tell("service unavailable, shutting down", self());
        }).
        match(Terminated.class, t -> t.actor().equals(worker), t -> {
          context().stop(self());
        }).build();
  }
  //#gracefulStop-actor

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


  public static class Cruncher extends AbstractActor {
    public Cruncher() {
      receive(ReceiveBuilder.
        matchEquals("crunch", s -> { }).build()
      );
    }
  }

  static
  //#swapper
  public class Swapper extends AbstractLoggingActor {
    public Swapper() {
      receive(ReceiveBuilder.
        matchEquals(Swap, s -> {
            log().info("Hi");
            context().become(ReceiveBuilder.
                    matchEquals(Swap, x -> {
                      log().info("Ho");
                      context().unbecome(); // resets the latest 'become' (just for fun)
                    }).build(), false); // push on top instead of replace
        }).build()
      );
    }
  }

  //#swapper
  static
  //#swapper
  public class SwapperApp {
    public static void main(String[] args) {
      ActorSystem system = ActorSystem.create("SwapperSystem");
      ActorRef swapper = system.actorOf(Props.create(Swapper.class), "swapper");
      swapper.tell(Swap, ActorRef.noSender()); // logs Hi
      swapper.tell(Swap, ActorRef.noSender()); // logs Ho
      swapper.tell(Swap, ActorRef.noSender()); // logs Hi
      swapper.tell(Swap, ActorRef.noSender()); // logs Ho
      swapper.tell(Swap, ActorRef.noSender()); // logs Hi
      swapper.tell(Swap, ActorRef.noSender()); // logs Ho
      system.terminate();
    }
  }
  //#swapper


  @Test
  public void creatingActorWithSystemActorOf() {
    //#system-actorOf
    // ActorSystem is a heavy object: create only one per application
    final ActorSystem system = ActorSystem.create("MySystem", config);
    final ActorRef myActor = system.actorOf(Props.create(MyActor.class), "myactor");
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
  public void creatingPropsConfig() {
    //#creating-props
    Props props1 = Props.create(MyActor.class);
    Props props2 = Props.create(ActorWithArgs.class,
      () -> new ActorWithArgs("arg")); // careful, see below
    Props props3 = Props.create(ActorWithArgs.class, "arg");
    //#creating-props

    //#creating-props-deprecated
    // NOT RECOMMENDED within another actor:
    // encourages to close over enclosing class
    Props props7 = Props.create(ActorWithArgs.class,
      () -> new ActorWithArgs("arg"));
    //#creating-props-deprecated
  }

  @Test(expected=IllegalArgumentException.class)
  public void creatingPropsIllegal() {
    //#creating-props-illegal
    // This will throw an IllegalArgumentException since some runtime
    // type information of the lambda is erased.
    // Use Props.create(actorClass, Creator) instead.
    Props props = Props.create(() -> new ActorWithArgs("arg"));
    //#creating-props-illegal
  }

  static
  //#receive-timeout
  public class ReceiveTimeoutActor extends AbstractActor {
    //#receive-timeout
    ActorRef target = context().system().deadLetters();
    //#receive-timeout
    public ReceiveTimeoutActor() {
      // To set an initial delay
      context().setReceiveTimeout(Duration.create("10 seconds"));

      receive(ReceiveBuilder.
        matchEquals("Hello", s -> {
          // To set in a response to a message
          context().setReceiveTimeout(Duration.create("1 second"));
          //#receive-timeout
          target = sender();
          target.tell("Hello world", self());
          //#receive-timeout
        }).
        match(ReceiveTimeout.class, r -> {
          // To turn it off
          context().setReceiveTimeout(Duration.Undefined());
          //#receive-timeout
          target.tell("timeout", self());
          //#receive-timeout
        }).build()
      );
    }
  }
  //#receive-timeout

  @Test
  public void using_receiveTimeout() {
    final ActorRef myActor = system.actorOf(Props.create(ReceiveTimeoutActor.class));
    new JavaTestKit(system) {
      {
        myActor.tell("Hello", getRef());
        expectMsgEquals("Hello world");
        expectMsgEquals("timeout");
      }
    };
  }

  static
  //#hot-swap-actor
  public class HotSwapActor extends AbstractActor {
    private PartialFunction<Object, BoxedUnit> angry;
    private PartialFunction<Object, BoxedUnit> happy;

    public HotSwapActor() {
      angry =
        ReceiveBuilder.
          matchEquals("foo", s -> {
            sender().tell("I am already angry?", self());
          }).
          matchEquals("bar", s -> {
            context().become(happy);
          }).build();

      happy = ReceiveBuilder.
        matchEquals("bar", s -> {
          sender().tell("I am already happy :-)", self());
        }).
        matchEquals("foo", s -> {
          context().become(angry);
        }).build();

      receive(ReceiveBuilder.
        matchEquals("foo", s -> {
          context().become(angry);
        }).
        matchEquals("bar", s -> {
          context().become(happy);
        }).build()
      );
    }
  }
  //#hot-swap-actor

  @Test
  public void using_hot_swap() {
    final ActorRef actor = system.actorOf(Props.create(HotSwapActor.class), "hot");
    new JavaTestKit(system) {
      {
        actor.tell("foo", getRef());
        actor.tell("foo", getRef());
        expectMsgEquals("I am already angry?");
        actor.tell("bar", getRef());
        actor.tell("bar", getRef());
        expectMsgEquals("I am already happy :-)");
        actor.tell("foo", getRef());
        actor.tell("foo", getRef());
        expectMsgEquals("I am already angry?");
        expectNoMsg(Duration.create(1, TimeUnit.SECONDS));
      }
    };
  }


  static
  //#stash
  public class ActorWithProtocol extends AbstractActorWithStash {
    public ActorWithProtocol() {
      receive(ReceiveBuilder.
        matchEquals("open", s -> {
          context().become(ReceiveBuilder.
            matchEquals("write", ws -> { /* do writing */ }).
            matchEquals("close", cs -> {
              unstashAll();
              context().unbecome();
            }).
            matchAny(msg -> stash()).build(), false);
        }).
        matchAny(msg -> stash()).build()
      );
    }
  }
  //#stash

  @Test
  public void using_Stash() {
    final ActorRef actor = system.actorOf(Props.create(ActorWithProtocol.class), "stash");
  }

  static
  //#watch
  public class WatchActor extends AbstractActor {
    private final ActorRef child = context().actorOf(Props.empty(), "target");
    private ActorRef lastSender = system.deadLetters();

    public WatchActor() {
      context().watch(child); // <-- this is the only call needed for registration

      receive(ReceiveBuilder.
        matchEquals("kill", s -> {
          context().stop(child);
          lastSender = sender();
        }).
        match(Terminated.class, t -> t.actor().equals(child), t -> {
          lastSender.tell("finished", self());
        }).build()
      );
    }
  }
  //#watch

  @Test
  public void using_watch() {
    ActorRef actor = system.actorOf(Props.create(WatchActor.class));

    new JavaTestKit(system) {
      {
        actor.tell("kill", getRef());
        expectMsgEquals("finished");
      }
    };
  }

  static
  //#identify
  public class Follower extends AbstractActor {
    final Integer identifyId = 1;

    public Follower(){
      ActorSelection selection = context().actorSelection("/user/another");
      selection.tell(new Identify(identifyId), self());

      receive(ReceiveBuilder.
        match(ActorIdentity.class, id -> id.getRef() != null, id -> {
          ActorRef ref = id.getRef();
          context().watch(ref);
          context().become(active(ref));
        }).
        match(ActorIdentity.class, id -> id.getRef() == null, id -> {
          context().stop(self());
        }).build()
      );
    }

    final PartialFunction<Object, BoxedUnit> active(final ActorRef another) {
      return ReceiveBuilder.
        match(Terminated.class, t -> t.actor().equals(another), t -> {
          context().stop(self());
        }).build();
    }
  }
  //#identify

  @Test
  public void using_Identify() {
    ActorRef a = system.actorOf(Props.empty());
    ActorRef b = system.actorOf(Props.create(Follower.class));

    new JavaTestKit(system) {
      {
        watch(b);
        system.stop(a);
        assertEquals(expectMsgClass(Duration.create(2, TimeUnit.SECONDS), Terminated.class).actor(), b);
      }
    };
  }

  public static class NoReceiveActor extends AbstractActor {
  }

  @Test
  public void noReceiveActor() {
    EventFilter ex1 = new ErrorFilter(ActorInitializationException.class);
    EventFilter[] ignoreExceptions = { ex1 };
    try {
      system.eventStream().publish(new TestEvent.Mute(immutableSeq(ignoreExceptions)));
      new JavaTestKit(system) {{
        final ActorRef victim = new EventFilter<ActorRef>(ActorInitializationException.class) {
          protected ActorRef run() {
            return system.actorOf(Props.create(NoReceiveActor.class), "victim");
          }
        }.message("Actor behavior has not been set with receive(...)").occurrences(1).exec();

        assertEquals(true, victim.isTerminated());
      }};
    } finally {
      system.eventStream().publish(new TestEvent.UnMute(immutableSeq(ignoreExceptions)));
    }
  }

  public static class MultipleReceiveActor extends AbstractActor {
    public MultipleReceiveActor() {
      receive(ReceiveBuilder.
          match(String.class, s1 -> s1.toLowerCase().equals("become"), s1 -> {
            sender().tell(s1.toUpperCase(), self());
            receive(ReceiveBuilder.
              match(String.class, s2 -> {
                sender().tell(s2.toLowerCase(), self());
              }).build()
            );
          }).
          match(String.class, s1 -> {
            sender().tell(s1.toUpperCase(), self());
          }).build()
      );
    }
  }

  @Test
  public void multipleReceiveActor() {
    EventFilter ex1 = new ErrorFilter(IllegalActorStateException.class);
    EventFilter[] ignoreExceptions = { ex1 };
    try {
      system.eventStream().publish(new TestEvent.Mute(immutableSeq(ignoreExceptions)));
      new JavaTestKit(system) {{
        new EventFilter<Boolean>(IllegalActorStateException.class) {
          protected Boolean run() {
            ActorRef victim = system.actorOf(Props.create(MultipleReceiveActor.class), "victim2");
            victim.tell("Foo", getRef());
            expectMsgEquals("FOO");
            victim.tell("bEcoMe", getRef());
            expectMsgEquals("BECOME");
            victim.tell("Foo", getRef());
            // if it's upper case, then the actor was restarted
            expectMsgEquals("FOO");
            return true;
          }
        }.message("Actor behavior has already been set with receive(...), " +
                "use context().become(...) to change it later").occurrences(1).exec();
      }};
    } finally {
      system.eventStream().publish(new TestEvent.UnMute(immutableSeq(ignoreExceptions)));
    }
  }

}
