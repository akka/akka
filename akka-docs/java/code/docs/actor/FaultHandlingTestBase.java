/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#testkit
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.SupervisorStrategy;
import static akka.actor.SupervisorStrategy.*;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import akka.dispatch.Await;
import static akka.pattern.Patterns.ask;
import scala.concurrent.util.Duration;
import akka.testkit.AkkaSpec;
import akka.testkit.TestProbe;

//#testkit
import akka.testkit.ErrorFilter;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import static java.util.concurrent.TimeUnit.SECONDS;
import akka.japi.Function;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;

//#testkit
public class FaultHandlingTestBase {
  //#testkit
  //#supervisor
  static public class Supervisor extends UntypedActor {

    //#strategy
    private static SupervisorStrategy strategy = new OneForOneStrategy(10, Duration.parse("1 minute"),
        new Function<Throwable, Directive>() {
          @Override
          public Directive apply(Throwable t) {
            if (t instanceof ArithmeticException) {
              return resume();
            } else if (t instanceof NullPointerException) {
              return restart();
            } else if (t instanceof IllegalArgumentException) {
              return stop();
            } else {
              return escalate();
            }
          }
        });

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    //#strategy

    public void onReceive(Object o) {
      if (o instanceof Props) {
        getSender().tell(getContext().actorOf((Props) o));
      } else {
        unhandled(o);
      }
    }
  }

  //#supervisor

  //#supervisor2
  static public class Supervisor2 extends UntypedActor {

    //#strategy2
    private static SupervisorStrategy strategy = new OneForOneStrategy(10, Duration.parse("1 minute"),
        new Function<Throwable, Directive>() {
          @Override
          public Directive apply(Throwable t) {
            if (t instanceof ArithmeticException) {
              return resume();
            } else if (t instanceof NullPointerException) {
              return restart();
            } else if (t instanceof IllegalArgumentException) {
              return stop();
            } else {
              return escalate();
            }
          }
        });

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    //#strategy2

    public void onReceive(Object o) {
      if (o instanceof Props) {
        getSender().tell(getContext().actorOf((Props) o));
      } else {
        unhandled(o);
      }
    }

    @Override
    public void preRestart(Throwable cause, Option<Object> msg) {
      // do not kill all children, which is the default here
    }
  }

  //#supervisor2

  //#child
  static public class Child extends UntypedActor {
    int state = 0;

    public void onReceive(Object o) throws Exception {
      if (o instanceof Exception) {
        throw (Exception) o;
      } else if (o instanceof Integer) {
        state = (Integer) o;
      } else if (o.equals("get")) {
        getSender().tell(state);
      } else {
        unhandled(o);
      }
    }
  }

  //#child

  //#testkit
  static ActorSystem system;
  Duration timeout = Duration.create(5, SECONDS);

  @BeforeClass
  public static void start() {
    system = ActorSystem.create("test", AkkaSpec.testConf());
  }

  @AfterClass
  public static void cleanup() {
    system.shutdown();
  }

  @Test
  public void mustEmploySupervisorStrategy() throws Exception {
    // code here
    //#testkit
    EventFilter ex1 = (EventFilter) new ErrorFilter(ArithmeticException.class);
    EventFilter ex2 = (EventFilter) new ErrorFilter(NullPointerException.class);
    EventFilter ex3 = (EventFilter) new ErrorFilter(IllegalArgumentException.class);
    EventFilter ex4 = (EventFilter) new ErrorFilter(Exception.class);
    Seq<EventFilter> ignoreExceptions = seq(ex1, ex2, ex3, ex4);
    system.eventStream().publish(new TestEvent.Mute(ignoreExceptions));

    //#create
    Props superprops = new Props(Supervisor.class);
    ActorRef supervisor = system.actorOf(superprops, "supervisor");
    ActorRef child = (ActorRef) Await.result(ask(supervisor, new Props(Child.class), 5000), timeout);
    //#create

    //#resume
    child.tell(42);
    assert Await.result(ask(child, "get", 5000), timeout).equals(42);
    child.tell(new ArithmeticException());
    assert Await.result(ask(child, "get", 5000), timeout).equals(42);
    //#resume

    //#restart
    child.tell(new NullPointerException());
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    //#restart

    //#stop
    final TestProbe probe = new TestProbe(system);
    probe.watch(child);
    child.tell(new IllegalArgumentException());
    probe.expectMsg(new Terminated(child, true));
    //#stop

    //#escalate-kill
    child = (ActorRef) Await.result(ask(supervisor, new Props(Child.class), 5000), timeout);
    probe.watch(child);
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    child.tell(new Exception());
    probe.expectMsg(new Terminated(child, true));
    //#escalate-kill

    //#escalate-restart
    superprops = new Props(Supervisor2.class);
    supervisor = system.actorOf(superprops, "supervisor2");
    child = (ActorRef) Await.result(ask(supervisor, new Props(Child.class), 5000), timeout);
    child.tell(23);
    assert Await.result(ask(child, "get", 5000), timeout).equals(23);
    child.tell(new Exception());
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    //#escalate-restart
    //#testkit
  }

  //#testkit
  public <A> Seq<A> seq(A... args) {
    return JavaConverters.collectionAsScalaIterableConverter(java.util.Arrays.asList(args)).asScala().toSeq();
  }
  //#testkit
}
//#testkit