/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda.fsm;

import akka.actor.*;
import akka.testkit.JavaTestKit;
import docs.AbstractJavaTest;
import org.hamcrest.CoreMatchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.Duration;

import static org.junit.Assert.*;

import static docs.actorlambda.fsm.FSMDocTest.StateType.*;
import static docs.actorlambda.fsm.FSMDocTest.Messages.*;
import static java.util.concurrent.TimeUnit.*;

public class FSMDocTest extends AbstractJavaTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FSMDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  public static enum StateType {
    SomeState,
    Processing,
    Idle,
    Active,
    Error
  }

  public static enum Messages {
    WillDo,
    Tick
  }

  public static enum Data {
    Foo,
    Bar
  };

  public static interface X {};

  public static class DummyFSM extends AbstractFSM<StateType, Integer> {
    Integer newData = 42;
    //#alt-transition-syntax
    public void handler(StateType from, StateType to) {
      // handle transition here
    }

    //#alt-transition-syntax
    {
    //#modifier-syntax
    when(SomeState, matchAnyEvent((msg, data) -> {
        return goTo(Processing).using(newData).
          forMax(Duration.create(5, SECONDS)).replying(WillDo);
    }));
    //#modifier-syntax

    //#NullFunction
    when(SomeState, AbstractFSM.NullFunction());
    //#NullFunction

    //#transition-syntax
    onTransition(
      matchState(Active, Idle, () -> setTimer("timeout",
        Tick, Duration.create(1, SECONDS), true)).
      state(Active, null, () -> cancelTimer("timeout")).
      state(null, Idle, (f, t) -> log().info("entering Idle from " + f)));
    //#transition-syntax

    //#alt-transition-syntax
    onTransition(this::handler);
    //#alt-transition-syntax

    //#stop-syntax
    when(Error, matchEventEquals("stop", (event, data) -> {
      // do cleanup ...
      return stop();
    }));
    //#stop-syntax

    //#termination-syntax
    onTermination(
      matchStop(Normal(),
        (state, data) -> {/* Do something here */}).
        stop(Shutdown(),
          (state, data) -> {/* Do something here */}).
        stop(Failure.class,
          (reason, state, data) -> {/* Do something here */}));
    //#termination-syntax

    //#unhandled-syntax
    whenUnhandled(
      matchEvent(X.class, (x, data) -> {
        log().info("Received unhandled event: " + x);
        return stay();
      }).
      anyEvent((event, data) -> {
        log().warning("Received unknown event: " + event);
        return goTo(Error);
      }));
    }
    //#unhandled-syntax
    }

  static
  //#logging-fsm
  public class MyFSM extends AbstractLoggingFSM<StateType, Data> {
    //#body-elided
    //#logging-fsm
    ActorRef target = null;
    //#logging-fsm
    @Override
    public int logDepth() { return 12; }
    {
      onTermination(
        matchStop(Failure.class, (reason, state, data) -> {
          String lastEvents = getLog().mkString("\n\t");
          log().warning("Failure in state " + state + " with data " + data + "\n" +
            "Events leading up to this point:\n\t" + lastEvents);
          //#logging-fsm
          target.tell(reason.cause(), self());
          target.tell(state, self());
          target.tell(data, self());
          target.tell(lastEvents, self());
          //#logging-fsm
        })
      );
      //...
      //#logging-fsm
      startWith(SomeState, Data.Foo);
      when(SomeState, matchEvent(ActorRef.class, Data.class, (ref, data) -> {
        target = ref;
        target.tell("going active", self());
        return goTo(Active);
      }));
      when(Active, matchEventEquals("stop", (event, data) -> {
        target.tell("stopping", self());
        return stop(new Failure("This is not the error you're looking for"));
      }));
      initialize();
      //#logging-fsm
    }
    //#body-elided
  }
  //#logging-fsm

  @Test
  public void testLoggingFSM()
  {
    new JavaTestKit(system) {{
      final ActorRef logger =
        system.actorOf(Props.create(MyFSM.class));
      final ActorRef probe = getRef();

      logger.tell(probe, probe);
      expectMsgEquals("going active");
      logger.tell("stop", probe);
      expectMsgEquals("stopping");
      expectMsgEquals("This is not the error you're looking for");
      expectMsgEquals(Active);
      expectMsgEquals(Data.Foo);
      String msg = expectMsgClass(String.class);
      assertTrue(msg.startsWith("LogEntry(SomeState,Foo,Actor[akka://FSMDocTest/system/"));
    }};
  }
}
