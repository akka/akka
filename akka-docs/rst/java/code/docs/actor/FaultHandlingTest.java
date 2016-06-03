/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

//#testkit
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.SupervisorStrategy;
import static akka.actor.SupervisorStrategy.resume;
import static akka.actor.SupervisorStrategy.restart;
import static akka.actor.SupervisorStrategy.stop;
import static akka.actor.SupervisorStrategy.escalate;
import akka.actor.SupervisorStrategy.Directive;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import docs.AbstractJavaTest;
import scala.collection.immutable.Seq;
import scala.concurrent.Await;
import static akka.pattern.Patterns.ask;
import scala.concurrent.duration.Duration;
import akka.testkit.TestProbe;

//#testkit
import akka.testkit.ErrorFilter;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import akka.testkit.JavaTestKit;
import static java.util.concurrent.TimeUnit.SECONDS;
import static akka.japi.Util.immutableSeq;
import akka.japi.Function;
import scala.Option;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;

//#testkit
public class FaultHandlingTest extends AbstractJavaTest {
  //#testkit
  static
  //#supervisor
  public class Supervisor extends UntypedActor {

    //#strategy
    private static SupervisorStrategy strategy =
      new OneForOneStrategy(10, Duration.create("1 minute"),
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
        getSender().tell(getContext().actorOf((Props) o), getSelf());
      } else {
        unhandled(o);
      }
    }
  }

  //#supervisor

  static
  //#supervisor2
  public class Supervisor2 extends UntypedActor {

    //#strategy2
    private static SupervisorStrategy strategy = new OneForOneStrategy(10,
      Duration.create("1 minute"),
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
        getSender().tell(getContext().actorOf((Props) o), getSelf());
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

  static
  //#child
  public class Child extends UntypedActor {
    int state = 0;

    public void onReceive(Object o) throws Exception {
      if (o instanceof Exception) {
        throw (Exception) o;
      } else if (o instanceof Integer) {
        state = (Integer) o;
      } else if (o.equals("get")) {
        getSender().tell(state, getSelf());
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
    system = ActorSystem.create("FaultHandlingTest");
  }

  @AfterClass
  public static void cleanup() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void mustEmploySupervisorStrategy() throws Exception {
    // code here
    //#testkit
    EventFilter ex1 = new ErrorFilter(ArithmeticException.class);
    EventFilter ex2 = new ErrorFilter(NullPointerException.class);
    EventFilter ex3 = new ErrorFilter(IllegalArgumentException.class);
    EventFilter ex4 = new ErrorFilter(Exception.class);
    EventFilter[] ignoreExceptions = { ex1, ex2, ex3, ex4 };
    Seq<EventFilter> seq = immutableSeq(ignoreExceptions);
    system.eventStream().publish(new TestEvent.Mute(seq));

    //#create
    Props superprops = Props.create(Supervisor.class);
    ActorRef supervisor = system.actorOf(superprops, "supervisor");
    ActorRef child = (ActorRef) Await.result(ask(supervisor,
      Props.create(Child.class), 5000), timeout);
    //#create

    //#resume
    child.tell(42, ActorRef.noSender());
    assert Await.result(ask(child, "get", 5000), timeout).equals(42);
    child.tell(new ArithmeticException(), ActorRef.noSender());
    assert Await.result(ask(child, "get", 5000), timeout).equals(42);
    //#resume

    //#restart
    child.tell(new NullPointerException(), ActorRef.noSender());
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    //#restart

    //#stop
    final TestProbe probe = new TestProbe(system);
    probe.watch(child);
    child.tell(new IllegalArgumentException(), ActorRef.noSender());
    probe.expectMsgClass(Terminated.class);
    //#stop

    //#escalate-kill
    child = (ActorRef) Await.result(ask(supervisor,
      Props.create(Child.class), 5000), timeout);
    probe.watch(child);
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    child.tell(new Exception(), ActorRef.noSender());
    probe.expectMsgClass(Terminated.class);
    //#escalate-kill

    //#escalate-restart
    superprops = Props.create(Supervisor2.class);
    supervisor = system.actorOf(superprops);
    child = (ActorRef) Await.result(ask(supervisor,
      Props.create(Child.class), 5000), timeout);
    child.tell(23, ActorRef.noSender());
    assert Await.result(ask(child, "get", 5000), timeout).equals(23);
    child.tell(new Exception(), ActorRef.noSender());
    assert Await.result(ask(child, "get", 5000), timeout).equals(0);
    //#escalate-restart
    //#testkit
  }

}
//#testkit
