/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

import akka.actor.*;


import akka.testkit.javadsl.TestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import jdocs.AbstractJavaTest;
import java.util.Optional;
import java.time.Duration;

import static akka.pattern.Patterns.ask;

//#testkit
import akka.testkit.TestProbe;
import akka.testkit.ErrorFilter;
import akka.testkit.EventFilter;
import akka.testkit.TestEvent;
import static java.util.concurrent.TimeUnit.SECONDS;
import static akka.japi.Util.immutableSeq;
import scala.concurrent.Await;

//#testkit

//#supervisor
import akka.japi.pf.DeciderBuilder;
import akka.actor.SupervisorStrategy;

//#supervisor

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;

//#testkit
public class FaultHandlingTest extends AbstractJavaTest {
//#testkit

  public static Config config = ConfigFactory.parseString(
    "akka {\n" +
    "  loggers = [\"akka.testkit.TestEventListener\"]\n" +
    "  loglevel = \"WARNING\"\n" +
    "  stdout-loglevel = \"WARNING\"\n" +
    "}\n");

  static
  //#supervisor
  public class Supervisor extends AbstractActor {

    //#strategy
    private static SupervisorStrategy strategy =
      new OneForOneStrategy(10, Duration.ofMinutes(1),
          DeciderBuilder
              .match(ArithmeticException.class, e -> SupervisorStrategy.resume())
              .match(NullPointerException.class, e -> SupervisorStrategy.restart())
              .match(IllegalArgumentException.class, e -> SupervisorStrategy.stop())
              .matchAny(o -> SupervisorStrategy.escalate())
              .build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    //#strategy

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Props.class, props -> {
          getSender().tell(getContext().actorOf(props), getSelf());
        })
        .build();
    }
  }

  //#supervisor

  static
  //#supervisor2
  public class Supervisor2 extends AbstractActor {

    //#strategy2
    private static SupervisorStrategy strategy =
      new OneForOneStrategy(10, Duration.ofMinutes(1), DeciderBuilder.
        match(ArithmeticException.class, e -> SupervisorStrategy.resume()).
        match(NullPointerException.class, e -> SupervisorStrategy.restart()).
        match(IllegalArgumentException.class, e -> SupervisorStrategy.stop()).
        matchAny(o -> SupervisorStrategy.escalate())
        .build());

    @Override
    public SupervisorStrategy supervisorStrategy() {
      return strategy;
    }

    //#strategy2

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Props.class, props -> {
          getSender().tell(getContext().actorOf(props), getSelf());
        })
        .build();
    }

    @Override
    public void preRestart(Throwable cause, Optional<Object> msg) {
      // do not kill all children, which is the default here
    }
  }

  //#supervisor2

  static
  //#child
  public class Child extends AbstractActor {
    int state = 0;

    @Override
    public Receive createReceive() {
      return receiveBuilder()
        .match(Exception.class, exception -> { throw exception; })
        .match(Integer.class, i -> state = i)
        .matchEquals("get", s -> getSender().tell(state, getSelf()))
        .build();
    }
  }

  //#child

  //#testkit
  static ActorSystem system;
  scala.concurrent.duration.Duration timeout = scala.concurrent.duration.Duration.create(5, SECONDS);

  @BeforeClass
  public static void start() {
    system = ActorSystem.create("FaultHandlingTest", config);
  }

  @AfterClass
  public static void cleanup() {
    TestKit.shutdownActorSystem(system);
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
    system.getEventStream().publish(new TestEvent.Mute(immutableSeq(ignoreExceptions)));

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
