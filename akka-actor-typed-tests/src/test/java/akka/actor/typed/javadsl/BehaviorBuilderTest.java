/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.javadsl;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatestplus.junit.JUnitSuite;

import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.ActorRef;

import java.util.ArrayList;

import static akka.actor.typed.javadsl.Behaviors.same;
import static akka.actor.typed.javadsl.Behaviors.stopped;
import static org.junit.Assert.assertEquals;

/** Test creating [[Behavior]]s using [[BehaviorBuilder]] */
public class BehaviorBuilderTest extends JUnitSuite {

  @ClassRule public static final TestKitJunitResource testKit = new TestKitJunitResource();

  interface Message {}

  static final class One implements Message {
    public String foo() {
      return "Bar";
    }
  }

  static final class MyList<T> extends ArrayList<T> implements Message {};

  public void shouldCompile() {
    Behavior<Message> b =
        Behaviors.receive(Message.class)
            .onMessage(
                One.class,
                (context, o) -> {
                  o.foo();
                  return same();
                })
            .onMessage(One.class, o -> o.foo().startsWith("a"), (context, o) -> same())
            .onMessageUnchecked(
                MyList.class,
                (ActorContext<Message> context, MyList<String> l) -> {
                  String first = l.get(0);
                  return Behaviors.<Message>same();
                })
            .onSignal(
                Terminated.class,
                (context, t) -> {
                  System.out.println("Terminating along with " + t.getRef());
                  return stopped();
                })
            .build();
  }

  @Test
  public void caseSelectedInOrderAdded() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        BehaviorBuilder.create()
            .onMessage(
                String.class,
                (context, msg) -> {
                  probe.ref().tell("handler 1: " + msg);
                  return Behaviors.same();
                })
            .onMessage(
                String.class,
                (context, msg) -> {
                  probe.ref().tell("handler 2: " + msg);
                  return Behaviors.same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("handler 1: message");
  }

  @Test
  public void handleMessageBasedOnEquality() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        BehaviorBuilder.create()
            .onMessageEquals(
                "message",
                (context) -> {
                  probe.ref().tell("got it");
                  return Behaviors.same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("got it");
  }

  @Test
  public void applyPredicate() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        BehaviorBuilder.create()
            .onMessage(
                String.class,
                (msg) -> "other".equals(msg),
                (context, msg) -> {
                  probe.ref().tell("handler 1: " + msg);
                  return Behaviors.same();
                })
            .onMessage(
                String.class,
                (context, msg) -> {
                  probe.ref().tell("handler 2: " + msg);
                  return Behaviors.same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("handler 2: message");
  }

  @Test
  public void catchAny() {
    final TestProbe<Object> probe = testKit.createTestProbe();
    Behavior<Object> behavior =
        BehaviorBuilder.create()
            .onAnyMessage(
                (context, msg) -> {
                  probe.ref().tell(msg);
                  return same();
                })
            .build();
    ActorRef<Object> ref = testKit.spawn(behavior);
    ref.tell("message");
    probe.expectMessage("message");
  }

  interface CounterMessage {};

  static final class Increase implements CounterMessage {};

  static final class Get implements CounterMessage {
    final ActorRef<Got> sender;

    public Get(ActorRef<Got> sender) {
      this.sender = sender;
    }
  };

  static final class Got {
    final int n;

    public Got(int n) {
      this.n = n;
    }
  }

  public Behavior<CounterMessage> immutableCounter(int currentValue) {
    return Behaviors.receive(CounterMessage.class)
        .onMessage(
            Increase.class,
            (context, o) -> {
              return immutableCounter(currentValue + 1);
            })
        .onMessage(
            Get.class,
            (context, o) -> {
              o.sender.tell(new Got(currentValue));
              return same();
            })
        .build();
  }

  @Test
  public void testImmutableCounter() {
    ActorRef<CounterMessage> ref = testKit.spawn(immutableCounter(0));
    TestProbe<Got> probe = testKit.createTestProbe();
    ref.tell(new Get(probe.getRef()));
    assertEquals(0, probe.expectMessageClass(Got.class).n);
    ref.tell(new Increase());
    ref.tell(new Get(probe.getRef()));
    assertEquals(1, probe.expectMessageClass(Got.class).n);
  }
}
