/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.actor;

import akka.actor.*;
import akka.event.LoggingAdapter;
import akka.event.Logging;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import static docs.actor.Messages.Swap.Swap;
import static docs.actor.Messages.*;

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

public class ActorDocTest {

  static ActorSystem system = null;

  @BeforeClass
  public static void beforeClass() {
    system = ActorSystem.create("ActorDocTest");
  }

  @AfterClass
  public static void afterClass() {
    system.shutdown();
    system.awaitTermination(Duration.create("5 seconds"));
  }

  static
  //#context-actorOf
  public class FirstActor extends AbstractActor {
    final ActorRef child = context().actorOf(Props.create(MyActor.class), "myChild");
    //#plus-some-behavior
    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchAny(x -> {
          sender().tell(x, self());
        }).build();
    }
    //#plus-some-behavior
  }
  //#context-actorOf

  static public abstract class ReceiveActor extends AbstractActor {
    @Override
    //#receive
    public abstract PartialFunction<Object, BoxedUnit> receive();
    //#receive
  }

  static public class ActorWithArgs extends AbstractActor {
    private final String args;

    ActorWithArgs(String args) {
      this.args = args;
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchAny(x -> { }).build();
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
    }

    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        match(Integer.class, i -> {
          sender().tell(i + magicNumber, self());
        }).build();
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
    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return emptyBehavior();
    }
    //#props-factory
  }
  //#props-factory

  public static class Hook extends AbstractActor {
    ActorRef target = null;
    //#preStart
    @Override
    public void preStart() {
      target = context().actorOf(Props.create(MyActor.class, "target"));
    }
    //#preStart
    public PartialFunction<Object, BoxedUnit> receive() {
      return emptyBehavior();
    }
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
    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
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
        }).build();
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

    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals("job", s -> {
          worker.tell("crunch", self());
        }).
        matchEquals(SHUTDOWN, x -> {
          worker.tell(PoisonPill.getInstance(), self());
          context().become(shuttingDown);
        }).build();
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
    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals("crunch", s -> { }).build();
    }
  }

  static
  //#swapper
  public class Swapper extends AbstractActor {
    final LoggingAdapter log = Logging.getLogger(context().system(), this);

    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals(Swap, s -> {
            log.info("Hi");
            context().become(ReceiveBuilder.
                    matchEquals(Swap, x -> {
                      log.info("Ho");
                      context().unbecome(); // resets the latest 'become' (just for fun)
                    }).build(), false); // push on top instead of replace
        }).build();
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
      system.shutdown();
    }
  }
  //#swapper


  @Test
  public void creatingActorWithSystemActorOf() {
    //#system-actorOf
    // ActorSystem is a heavy object: create only one per application
    final ActorSystem system = ActorSystem.create("MySystem");
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
    public ReceiveTimeoutActor() {
      // To set an initial delay
      context().setReceiveTimeout(Duration.create("10 seconds"));
    }
    //#receive-timeout
    ActorRef target = context().system().deadLetters();
    //#receive-timeout

    @Override
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
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
        }).build();
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

    {
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
    }

    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals("foo", s -> {
          context().become(angry);
        }).
        matchEquals("bar", s -> {
          context().become(happy);
        }).build();
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
    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals("open", s -> {
          context().become(ReceiveBuilder.
            matchEquals("write", ws -> { /* do writing */ }).
            matchEquals("close", cs -> {
              unstashAll();
              context().unbecome();
            }).
            matchAny(msg -> stash()).build(), false);
        }).
        matchAny(msg -> stash()).build();
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
    {
      context().watch(child); // <-- this is the only call needed for registration
    }
    private ActorRef lastSender = system.deadLetters();

    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        matchEquals("kill", s -> {
          context().stop(child);
          lastSender = sender();
        }).
        match(Terminated.class, t -> t.actor().equals(child), t -> {
          lastSender.tell("finished", self());
        }).build();
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

    {
      ActorSelection selection = context().actorSelection("/user/another");
      selection.tell(new Identify(identifyId), self());
    }

    public PartialFunction<Object, BoxedUnit> receive() {
      return ReceiveBuilder.
        match(ActorIdentity.class, id -> id.getRef() != null, id -> {
          ActorRef ref = id.getRef();
          context().watch(ref);
          context().become(active(ref));
        }).
        match(ActorIdentity.class, id -> id.getRef() == null, id -> {
          context().stop(self());
        }).build();
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
}
